package edu.stanford.nlp.naturalli;

import edu.stanford.nlp.hcoref.data.CorefChain;
import edu.stanford.nlp.hcoref.CorefCoreAnnotations;
import edu.stanford.nlp.ie.util.RelationTriple;
import edu.stanford.nlp.international.Language;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.io.RuntimeIOException;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.pipeline.*;
import edu.stanford.nlp.semgraph.SemanticGraph;
import edu.stanford.nlp.semgraph.SemanticGraphCoreAnnotations;
import edu.stanford.nlp.semgraph.SemanticGraphEdge;
import edu.stanford.nlp.semgraph.semgrex.SemgrexMatcher;
import edu.stanford.nlp.semgraph.semgrex.SemgrexPattern;
import edu.stanford.nlp.stats.ClassicCounter;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.Counters;
import edu.stanford.nlp.trees.GrammaticalRelation;
import edu.stanford.nlp.trees.UniversalEnglishGrammaticalRelations;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Execution;
import edu.stanford.nlp.util.Pair;
import edu.stanford.nlp.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * <p>
 * An OpenIE system based on valid Natural Logic deletions of a sentence.
 * The system is described in:
 * </p>
 *
 * <pre>
 *   "Leveraging Linguistic Structure For Open Domain Information Extraction." Gabor Angeli, Melvin Johnson Premkumar, Christopher Manning. ACL 2015.
 * </pre>
 *
 * <p>
 * The paper can be found at <a href="http://nlp.stanford.edu/pubs/2015angeli-openie.pdf">http://nlp.stanford.edu/pubs/2015angeli-openie.pdf</a>.
 * </p>

 * <p>
 * Documentation on the system can be found on
 * <a href="http://nlp.stanford.edu/software/openie.shtml">the project homepage</a>,
 * or the <a href="http://stanfordnlp.github.io/CoreNLP/openie.html">CoreNLP annotator documentation page</a>.
 * The simplest invocation of the system would be something like:
 * </p>
 *
 * <pre>
 * java -mx1g -cp stanford-openie.jar:stanford-openie-models.jar edu.stanford.nlp.naturalli.OpenIE
 * </pre>
 *
 * <p>
 *   Note that this class serves both as an entry point for the OpenIE system, but also as a CoreNLP annotator
 *   which can be plugged into the CoreNLP pipeline (or any other annotation pipeline).
 * </p>
 *
 * @see OpenIE#annotate(Annotation)
 * @see OpenIE#main(String[])
 *
 * @author Gabor Angeli
 */
//
// TODO(gabor): handle lists ("She was the sovereign of Austria, Hungary, Croatia, Bohemia, Mantua, Milan, Lodomeria and Galicia.")
// TODO(gabor): handle things like "One example of chemical energy is that found in the food that we eat ."
//
@SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"})
public class OpenIE implements Annotator {

  private enum OutputFormat { REVERB, OLLIE, DEFAULT }

  /**
   * A pattern for rewriting "NN_1 is a JJ NN_2" --> NN_1 is JJ"
   */
  private static SemgrexPattern adjectivePattern = SemgrexPattern.compile("{}=obj >nsubj {}=subj >cop {}=be >det {word:/an?/} >amod {}=adj ?>/prep_.*/=prep {}=pobj");

  //
  // Static Options (for running standalone)
  //

  @Execution.Option(name="format", gloss="The format to output the triples in.")
  private static OutputFormat FORMAT = OutputFormat.DEFAULT;

  @Execution.Option(name="filelist", gloss="The files to annotate, as a list of files one per line.")
  private static File FILELIST  = null;

  //
  // Annotator Options (for running in the pipeline)
  //
  @Execution.Option(name="splitter.model", gloss="The location of the clause splitting model.")
  private String splitterModel = DefaultPaths.DEFAULT_OPENIE_CLAUSE_SEARCHER;

  @Execution.Option(name="splitter.nomodel", gloss="If true, don't load a clause splitter model. This is primarily useful for training.")
  private boolean noModel = false;

  @Execution.Option(name="splitter.threshold", gloss="The minimum threshold for accepting a clause.")
  private double splitterThreshold = 0.1;

  @Execution.Option(name="splitter.disable", gloss="If true, don't run the sentence splitter")
  private boolean splitterDisable = false;

  @Execution.Option(name="max_entailments_per_clause", gloss="The maximum number of entailments allowed per sentence of input.")
  private int entailmentsPerSentence = 1000;

  @Execution.Option(name="ignore_affinity", gloss="If true, don't use the affinity models for dobj and pp attachment.")
  private boolean ignoreAffinity = false;

  @Execution.Option(name="affinity_models", gloss="The directory (or classpath directory) containing the affinity models for pp/obj attachments.")
  private String affinityModels = DefaultPaths.DEFAULT_NATURALLI_AFFINITIES;

  @Execution.Option(name="affinity_probability_cap", gloss="The affinity to consider 1.0")
  private double affinityProbabilityCap = 1.0 / 3.0;

  @Execution.Option(name="triple.strict", gloss="If true, only generate triples if the entire fragment has been consumed.")
  private boolean consumeAll = true;

  @Execution.Option(name="triple.all_nominals", gloss="If true, generate not only named entity nominal relations.")
  private boolean allNominals = false;

  @Execution.Option(name="resolve_coref", gloss="If true, resolve pronouns to their canonical mention")
  private boolean resolveCoref = false;

  /**
   * The natural logic weights loaded from the models file.
   * This is primarily the prepositional attachment statistics.
   */
  private final NaturalLogicWeights weights;

  /**
   * The clause splitter model, if one is to be used.
   * This component splits a sentence into a set of entailed clauses, but does not yet
   * maximally shorten them.
   * This is the implementation of stage 1 of the OpenIE pipeline.
   */
  public final Optional<ClauseSplitter> clauseSplitter;

  /**
   * The forward entailer model, running a search from clauses to maximally shortened clauses.
   * This is the implementation of stage 2 of the OpenIE pipeline.
   */
  public final ForwardEntailer forwardEntailer;

  /**
   * The relation triple segmenter, which converts a maximally shortened clause into an OpenIE
   * extraction triple.
   * This is the implementation of stage 3 of the OpenIE pipeline.
   */
  public RelationTripleSegmenter segmenter;


  /** Create a new OpenIE system, with default properties */
  @SuppressWarnings("UnusedDeclaration")
  public OpenIE() {
    this(new Properties());
  }


  /**
   * Create a ne OpenIE system, based on the given properties.
   * @param props The properties to parametrize the system with.
   */
  public OpenIE(Properties props) {
    // Fill the properties
    Execution.fillOptions(this, props);
    Properties withoutOpenIEPrefix = new Properties();
    Enumeration<Object> keys = props.keys();
    while (keys.hasMoreElements()) {
      String key = keys.nextElement().toString();
      withoutOpenIEPrefix.setProperty(key.replace("openie.", ""), props.getProperty(key));
    }
    Execution.fillOptions(this, withoutOpenIEPrefix);

    // Create the clause splitter
    try {
      if (splitterDisable) {
        clauseSplitter = Optional.empty();
      } else {
        if (noModel) {
          System.err.println("Not loading a splitter model");
          clauseSplitter = Optional.of(ClauseSplitterSearchProblem::new);
        } else {
          clauseSplitter = Optional.of(ClauseSplitter.load(splitterModel));
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
      throw new RuntimeIOException("Could not load clause splitter model at " + splitterModel + ": " + e.getClass() + ": " + e.getMessage());
    }

    // Create the forward entailer
    try {
      this.weights = ignoreAffinity ? new NaturalLogicWeights(affinityProbabilityCap) : new NaturalLogicWeights(affinityModels, affinityProbabilityCap);
    } catch (IOException e) {
      throw new RuntimeIOException("Could not load affinity model at " + affinityModels + ": " + e.getMessage());
    }
    forwardEntailer = new ForwardEntailer(entailmentsPerSentence, weights);

    // Create the relation segmenter
    segmenter = new RelationTripleSegmenter(allNominals);
  }

  /**
   * Find the clauses in a sentence, where the sentence is expressed as a dependency tree.
   *
   * @param tree The dependency tree representation of the sentence.
   * @param assumedTruth The assumed truth of the sentence. This is almost always true, unless you are
   *                     doing some more nuanced reasoning.
   *
   * @return A set of clauses extracted from the sentence. This includes the original sentence.
   */
  @SuppressWarnings("unchecked")
  public List<SentenceFragment> clausesInSentence(SemanticGraph tree, boolean assumedTruth) {
    if (clauseSplitter.isPresent()) {
      return clauseSplitter.get().apply(tree, assumedTruth).topClauses(splitterThreshold);
    } else {
      return Collections.EMPTY_LIST;
    }
  }

  /**
   * Find the clauses in a sentence.
   * This runs the clause splitting component of the OpenIE system only.
   *
   * @see OpenIE#clausesInSentence(SemanticGraph, boolean)
   *
   * @param sentence The raw sentence to extract clauses from.
   *
   * @return A set of clauses extracted from the sentence. This includes the original sentence.
   */
  public List<SentenceFragment> clausesInSentence(CoreMap sentence) {
    return clausesInSentence(sentence.get(SemanticGraphCoreAnnotations.CollapsedDependenciesAnnotation.class), true);
  }

  /**
   * Returns all of the entailed shortened clauses (as per natural logic) from the given clause.
   * This runs the forward entailment component of the OpenIE system only.
   * It is usually chained together with the clause splitting component: {@link OpenIE#clausesInSentence(CoreMap)}.
   *
   * @param clause The premise clause, as a sentence fragment in itself.
   *
   * @return A list of entailed clauses.
   */
  @SuppressWarnings("unchecked")
  public List<SentenceFragment> entailmentsFromClause(SentenceFragment clause) {
    if (clause.parseTree.size() == 0) {
      return Collections.EMPTY_LIST;
    } else {
      // Get the forward entailments
      List<SentenceFragment> list = new ArrayList<>();
      if (entailmentsPerSentence > 0) {
        list.addAll(forwardEntailer.apply(clause.parseTree, true).search()
            .stream().map(x -> x.changeScore(x.score * clause.score)).collect(Collectors.toList()));
      }
      list.add(clause);

      // A special case for adjective entailments
      List<SentenceFragment> adjFragments = new ArrayList<>();
      SemgrexMatcher matcher = adjectivePattern.matcher(clause.parseTree);
      OUTER: while (matcher.find()) {
        // (get nodes)
        IndexedWord subj = matcher.getNode("subj");
        IndexedWord be = matcher.getNode("be");
        IndexedWord adj = matcher.getNode("adj");
        IndexedWord obj = matcher.getNode("obj");
        IndexedWord pobj = matcher.getNode("pobj");
        String prep = matcher.getRelnString("prep");
        // (if the adjective, or any earlier adjective, is privative, then all bets are off)
        for (SemanticGraphEdge edge : clause.parseTree.outgoingEdgeIterable(obj)) {
          if ("amod".equals(edge.getRelation().toString()) && edge.getDependent().index() <= adj.index() &&
              Util.PRIVATIVE_ADJECTIVES.contains(edge.getDependent().word().toLowerCase())) {
            continue OUTER;
          }
        }
        // (create the core tree)
        SemanticGraph tree = new SemanticGraph();
        tree.addRoot(adj);
        tree.addVertex(subj);
        tree.addVertex(be);
        tree.addEdge(adj, be, GrammaticalRelation.valueOf(Language.English, "cop"), Double.NEGATIVE_INFINITY, false);
        tree.addEdge(adj, subj, GrammaticalRelation.valueOf(Language.English, "nsubj"), Double.NEGATIVE_INFINITY, false);
        // (add pp attachment, if it existed)
        if (pobj != null) {
          assert prep != null;
          tree.addEdge(adj, pobj, GrammaticalRelation.valueOf(Language.English, prep), Double.NEGATIVE_INFINITY, false);
        }
        // (check for monotonicity)
        if (adj.get(NaturalLogicAnnotations.PolarityAnnotation.class).isUpwards() &&
            be.get(NaturalLogicAnnotations.PolarityAnnotation.class).isUpwards()) {
          // (add tree)
          adjFragments.add(new SentenceFragment(tree, clause.assumedTruth, false));
        }
      }
      list.addAll(adjFragments);
      return list;
    }
  }

  /**
   * Returns all the maximally shortened entailed fragments (as per natural logic)
   * from the given collection of clauses.
   *
   * @param clauses The clauses to shorten further.
   *
   * @return A set of sentence fragments corresponding to the maximally shortened entailed clauses.
   */
  public Set<SentenceFragment> entailmentsFromClauses(Collection<SentenceFragment> clauses) {
    Set<SentenceFragment> entailments = new HashSet<>();
    for (SentenceFragment clause : clauses) {
      entailments.addAll(entailmentsFromClause(clause));
    }
    return entailments;
  }

  /**
   * Returns the possible relation triple in this sentence fragment.
   *
   * @see OpenIE#relationInFragment(SentenceFragment, CoreMap)
   */
  public Optional<RelationTriple> relationInFragment(SentenceFragment fragment) {
    return segmenter.segment(fragment.parseTree, Optional.of(fragment.score), consumeAll);
  }

  /**
   * Returns the possible relation triple in this set of sentence fragments.
   *
   * @see OpenIE#relationsInFragments(Collection, CoreMap)
   */
  public List<RelationTriple> relationsInFragments(Collection<SentenceFragment> fragments) {
    return fragments.stream().map(this::relationInFragment).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toList());
  }

  /**
   * Returns the possible relation triple in this sentence fragment.
   *
   * @param fragment The sentence fragment to try to extract relations from.
   * @param sentence The containing sentence for the fragment.
   *
   * @return A relation triple if we could find one; otherwise, {@link Optional#empty()}.
   */
  private Optional<RelationTriple> relationInFragment(SentenceFragment fragment, CoreMap sentence) {
    return segmenter.segment(fragment.parseTree, Optional.of(fragment.score), consumeAll);
  }

  /**
   * Returns a list of OpenIE relations from the given set of sentence fragments.
   *
   * @param fragments The sentence fragments to extract relations from.
   * @param sentence The containing sentence that these fragments were extracted from.
   *
   * @return A list of OpenIE triples, corresponding to all the triples that could be extracted from the given fragments.
   */
  private List<RelationTriple> relationsInFragments(Collection<SentenceFragment> fragments, CoreMap sentence) {
    return fragments.stream().map(x -> relationInFragment(x, sentence)).filter(Optional::isPresent).map(Optional::get).collect(Collectors.toList());
  }

  /**
   * Extract the relations in this clause.
   *
   * @see OpenIE#entailmentsFromClause(SentenceFragment)
   * @see OpenIE#relationsInFragments(Collection)
   */
  public List<RelationTriple> relationsInClause(SentenceFragment clause) {
    return relationsInFragments(entailmentsFromClause(clause));
  }

  /**
   * Extract the relations in this sentence.
   *
   * @see OpenIE#clausesInSentence(CoreMap)
   * @see OpenIE#entailmentsFromClause(SentenceFragment)
   * @see OpenIE#relationsInFragments(Collection)
   */
  public List<RelationTriple> relationsInSentence(CoreMap sentence) {
    return relationsInFragments(entailmentsFromClauses(clausesInSentence(sentence)));
  }


  /**
   * Create a copy of the passed parse tree, canonicalizing pronominal nodes with their canonical mention.
   * Canonical mentions are tied together with the <i>compound</i> dependency arc; otherwise, the structure of
   * the tree remains unchanged.
   *
   * @param parse The original dependency parse of the sentence.
   * @param canonicalMentionMap The map from tokens to their canonical mentions.
   *
   * @return A <b>copy</b> of the passed parse tree, with pronouns replaces with their canonical mention.
   */
  private SemanticGraph canonicalizeCoref(SemanticGraph parse, Map<CoreLabel, List<CoreLabel>> canonicalMentionMap) {
    parse = new SemanticGraph(parse);
    for (IndexedWord node : new HashSet<>(parse.vertexSet())) {  // copy the vertex set to prevent ConcurrentModificationExceptions
      if (node.tag() != null && node.tag().startsWith("PRP")) {
        List<CoreLabel> canonicalMention = canonicalMentionMap.get(node.backingLabel());
        if (canonicalMention != null) {
          // Case: this node is a preposition with a valid antecedent.
          // 1. Save the attaching edges
          List<SemanticGraphEdge> incomingEdges = parse.incomingEdgeList(node);
          List<SemanticGraphEdge> outgoingEdges = parse.outgoingEdgeList(node);
          // 2. Remove the node
          parse.removeVertex(node);
          // 3. Add the new head word
          IndexedWord headWord = new IndexedWord(canonicalMention.get(canonicalMention.size() - 1));
          headWord.setPseudoPosition(node.pseudoPosition());
          parse.addVertex(headWord);
          for (SemanticGraphEdge edge : incomingEdges) {
            parse.addEdge(edge.getGovernor(), headWord, edge.getRelation(), edge.getWeight(), edge.isExtra());
          }
          for (SemanticGraphEdge edge : outgoingEdges) {
            parse.addEdge(headWord, edge.getDependent(), edge.getRelation(), edge.getWeight(), edge.isExtra());
          }
          // 4. Add other words
          double pseudoPosition = headWord.pseudoPosition() - 1e-3;
          for (int i = canonicalMention.size() - 2; i >= 0; --i) {
            // Create the node
            IndexedWord dependent = new IndexedWord(canonicalMention.get(i));
            // Set its pseudo position appropriately
            dependent.setPseudoPosition(pseudoPosition);
            pseudoPosition -= 1e-3;
            // Add the node to the graph
            parse.addVertex(dependent);
            parse.addEdge(headWord, dependent, UniversalEnglishGrammaticalRelations.COMPOUND_MODIFIER, 1.0, false);
          }

        }
      }
    }
    return parse;
  }

  /**
   * <p>
   *   Annotate a single sentence.
   * </p>
   * <p>
   *   This annotator will, in particular, set the {@link edu.stanford.nlp.naturalli.NaturalLogicAnnotations.EntailedSentencesAnnotation}
   *   and {@link edu.stanford.nlp.naturalli.NaturalLogicAnnotations.RelationTriplesAnnotation} annotations.
   * </p>
   */
  @SuppressWarnings("unchecked")
  public void annotateSentence(CoreMap sentence, Map<CoreLabel, List<CoreLabel>> canonicalMentionMap) {
    List<CoreLabel> tokens = sentence.get(CoreAnnotations.TokensAnnotation.class);
    if (tokens.size() < 2) {

      // Short sentence; skip annotating it.
      sentence.set(NaturalLogicAnnotations.RelationTriplesAnnotation.class, Collections.EMPTY_LIST);
      sentence.set(NaturalLogicAnnotations.EntailedSentencesAnnotation.class, Collections.EMPTY_SET);

    } else {

      // Get the dependency tree
      SemanticGraph parse = sentence.get(SemanticGraphCoreAnnotations.CollapsedDependenciesAnnotation.class);
      if (parse == null) {
        parse = sentence.get(SemanticGraphCoreAnnotations.BasicDependenciesAnnotation.class);
      }
      if (parse == null) {
        throw new IllegalStateException("Cannot run OpenIE without a parse tree!");
      }
      // Clean the tree
      Util.cleanTree(parse);

      // Resolve Coreference
      SemanticGraph canonicalizedParse = parse;
      if (resolveCoref && !canonicalMentionMap.isEmpty()) {
        canonicalizedParse = canonicalizeCoref(parse, canonicalMentionMap);
      }

      // Run OpenIE
      // (clauses)
      List<SentenceFragment> clauses = clausesInSentence(canonicalizedParse, true);  // note: uses coref-canonicalized parse
      // (entailment)
      Set<SentenceFragment> fragments = entailmentsFromClauses(clauses);
      // (segment)
      List<RelationTriple> extractions = segmenter.extract(parse, tokens);  // note: uses non-coref-canonicalized parse!
      extractions.addAll(relationsInFragments(fragments, sentence));


      // Set the annotations
      sentence.set(NaturalLogicAnnotations.EntailedSentencesAnnotation.class, fragments);
      sentence.set(NaturalLogicAnnotations.RelationTriplesAnnotation.class,
          new ArrayList<>(new HashSet<>(extractions)));  // uniq the extractions
    }
  }


  /**
   * {@inheritDoc}
   *
   * <p>
   *   This annotator will, in particular, set the {@link edu.stanford.nlp.naturalli.NaturalLogicAnnotations.EntailedSentencesAnnotation}
   *   and {@link edu.stanford.nlp.naturalli.NaturalLogicAnnotations.RelationTriplesAnnotation} annotations.
   * </p>
   */
  @Override
  public void annotate(Annotation annotation) {
    // Accumulate Coref data
    Map<Integer, CorefChain> corefChains = annotation.get(CorefCoreAnnotations.CorefChainAnnotation.class);
    Map<CoreLabel, List<CoreLabel>> canonicalMentionMap = new IdentityHashMap<>();
    if (corefChains != null && resolveCoref) {
      for (CorefChain chain : corefChains.values()) {
        // Make sure it's a real chain and not a singleton
        if (chain.getMentionsInTextualOrder().size() < 2) {
          continue;
        }

        // Metadata
        List<CoreLabel> canonicalMention = null;
        double canonicalMentionScore = Double.NEGATIVE_INFINITY;
        Set<CoreLabel> tokensToMark = new HashSet<>();
        List<CorefChain.CorefMention> mentions = chain.getMentionsInTextualOrder();

        // Iterate over mentions
        for (int i = 0; i < mentions.size(); ++i) {
          // Get some data on this mention
          Pair<List<CoreLabel>, Double> info = grokCorefMention(annotation, mentions.get(i));
          // Figure out if it should be the canonical mention
          double score = info.second + ((double) i) / ((double) mentions.size()) + (mentions.get(i) == chain.getRepresentativeMention() ? 1.0 : 0.0);
          if (canonicalMention == null || score > canonicalMentionScore) {
            canonicalMention = info.first;
            canonicalMentionScore = score;
          }

          // Register the participating tokens
          if (info.first.size() == 1) {  // Only mark single-node tokens!
            tokensToMark.addAll(info.first);
          }
        }

        // Mark the tokens as coreferent
        assert canonicalMention != null;
        for (CoreLabel token : tokensToMark) {
          List<CoreLabel> existingMention = canonicalMentionMap.get(token);
          if (existingMention == null || existingMention.isEmpty() ||
              "O".equals(existingMention.get(0).ner())) {  // Don't clobber existing good mentions
            canonicalMentionMap.put(token, canonicalMention);
          }
        }

      }
    }

    // Annotate each sentence
    annotation.get(CoreAnnotations.SentencesAnnotation.class).forEach(x -> this.annotateSentence(x, canonicalMentionMap));
  }

  /** {@inheritDoc} */
  @Override
  public Set<Requirement> requirementsSatisfied() {
    return Collections.singleton(Annotator.OPENIE_REQUIREMENT);
  }

  /** {@inheritDoc} */
  @Override
  public Set<Requirement> requires() {
    return Annotator.REQUIREMENTS.get(STANFORD_OPENIE);
  }

  /**
   * A utility to get useful information out of a CorefMention. In particular, it returns the CoreLabels which are
   * associated with this mention, and it returns a score for how much we think this mention should be the canonical
   * mention.
   *
   * @param doc The document this mention is referenced into.
   * @param mention The mention itself.
   * @return A pair of the tokens in the mention, and a score for how much we like this mention as the canonical mention.
   */
  private static Pair<List<CoreLabel>, Double> grokCorefMention(Annotation doc, CorefChain.CorefMention mention) {
    List<CoreLabel> tokens = doc.get(CoreAnnotations.SentencesAnnotation.class).get(mention.sentNum - 1).get(CoreAnnotations.TokensAnnotation.class);
    List<CoreLabel> mentionAsTokens = tokens.subList(mention.startIndex - 1, mention.endIndex - 1);
    // Try to assess this mention's NER type
    Counter<String> nerVotes = new ClassicCounter<>();
    mentionAsTokens.stream().filter(token -> token.ner() != null && !"O".equals(token.ner())).forEach(token -> nerVotes.incrementCount(token.ner()));
    String ner = Counters.argmax(nerVotes, (o1, o2) -> o1 == null ? 0 : o1.compareTo(o2));
    double nerCount = nerVotes.getCount(ner);
    double nerScore = nerCount * nerCount / ((double) mentionAsTokens.size());
    // Return
    return Pair.makePair(mentionAsTokens, nerScore);
  }

  /**
   * Process a single file or line of standard in.
   * @param pipeline The annotation pipeline to run the lines of the input through.
   * @param docid The docid of the document we are extracting.
   * @param document the document to annotate.
   */
  private static void processDocument(AnnotationPipeline pipeline, String docid, String document) {
    // Error checks
    if (document.trim().equals("")) {
      return;
    }

    // Annotate the document
    Annotation ann = new Annotation(document);
    pipeline.annotate(ann);

    // Get the extractions
    boolean empty = true;
    synchronized (System.out) {
      for (CoreMap sentence : ann.get(CoreAnnotations.SentencesAnnotation.class)) {
        for (RelationTriple extraction : sentence.get(NaturalLogicAnnotations.RelationTriplesAnnotation.class)) {
          // Print the extractions
          switch (FORMAT) {
            case REVERB:
              System.out.println(extraction.toReverbString(docid, sentence));
              break;
            case OLLIE:
              System.out.println(extraction.confidenceGloss() + ": (" + extraction.subjectGloss() + "; " + extraction.relationGloss() + "; " + extraction.objectGloss() + ")");
              break;
            case DEFAULT:
              System.out.println(extraction.toString());
              break;
            default:
              throw new IllegalStateException("Format is not implemented: " + FORMAT);
          }
          empty = false;
        }
      }
    }
    if (empty) {
      System.err.println("No extractions in: " + ("stdin".equals(docid) ? document : docid));
    }
  }

  /**
   * An entry method for annotating standard in with OpenIE extractions.
   */
  public static void main(String[] args) throws IOException, InterruptedException {
    // Parse the arguments
    Properties props = StringUtils.argsToProperties(args);
    Execution.fillOptions(new Class[]{ OpenIE.class, Execution.class}, props);
    AtomicInteger exceptionCount = new AtomicInteger(0);
    ExecutorService exec = Executors.newFixedThreadPool(Execution.threads);

    // Parse the files to process
    String[] filesToProcess;
    if (FILELIST != null) {
      filesToProcess = IOUtils.linesFromFile(FILELIST.getPath()).stream().map(String::trim).toArray(String[]::new);
    } else if (!"".equals(props.getProperty("", ""))) {
      filesToProcess = props.getProperty("", "").split("\\s+");
    } else {
      filesToProcess = new String[0];
    }

    // Tweak the arguments
    if ("".equals(props.getProperty("annotators", ""))) {
      if (!"false".equalsIgnoreCase(props.getProperty("resolve_coref", props.getProperty("openie.resolve_coref", "false")))) {
        props.setProperty("annotators", "tokenize,ssplit,pos,lemma,depparse,ner,entitymentions,coref,natlog,openie");
      } else {
        props.setProperty("annotators", "tokenize,ssplit,pos,lemma,depparse,natlog,openie");
      }
    }
    if ("".equals(props.getProperty("depparse.extradependencies", ""))) {
      props.setProperty("depparse.extradependencies", "ref_only_uncollapsed");
    }
    if ("".equals(props.getProperty("parse.extradependencies", ""))) {
      props.setProperty("parse.extradependencies", "ref_only_uncollapsed");
    }
    if ("".equals(props.getProperty("tokenize.class", ""))) {
      props.setProperty("tokenize.class", "PTBTokenizer");
    }
    if ("".equals(props.getProperty("tokenize.language", ""))) {
      props.setProperty("tokenize.language", "en");
    }
    // Tweak properties for console mode.
    // In particular, in this mode we can assume every line of standard in is a new sentence.
    if (filesToProcess.length == 0 && "".equals(props.getProperty("ssplit.isOneSentence", ""))) {
      props.setProperty("ssplit.isOneSentence", "ref_only_uncollapsed");
    }
    // Some error checks on the arguments
    if (!props.getProperty("annotators").toLowerCase().contains("openie")) {
      System.err.println("ERROR: If you specify custom annotators, you must at least include 'openie'");
      System.exit(1);
    }
    // Copy properties that are missing the 'openie' prefix
    new HashSet<>(props.keySet()).stream().filter(key -> !key.toString().startsWith("openie.")).forEach(key -> props.setProperty("openie." + key.toString(), props.getProperty(key.toString())));

    // Create the pipeline
    StanfordCoreNLP pipeline = new StanfordCoreNLP(props);

    // Run OpenIE
    if (filesToProcess.length == 0) {
      // Running from stdin; one document per line.
      System.err.println("Processing from stdin. Enter one sentence per line.");
      Scanner scanner = new Scanner(System.in);
      String line;
      try {
        line = scanner.nextLine();
      } catch (NoSuchElementException e) {
        System.err.println("No lines found on standard in");
        return;
      }
      while (line != null) {
        processDocument(pipeline, "stdin", line);
        try {
          line = scanner.nextLine();
        } catch (NoSuchElementException e) {
          return;
        }
      }
    } else {
      // Running from file parameters.
      // Make sure we can read all the files in the queue.
      // This will prevent a nasty surprise 10 hours into a running job...
      for (String file : filesToProcess) {
        if (!new File(file).exists() || !new File(file).canRead()) {
          System.err.println("ERROR: Cannot read file (or file does not exist: '" + file + "'");
        }
      }
      // Actually process the files.
      for (String file : filesToProcess) {
        System.err.println("Processing file: " + file);
        if (Execution.threads > 1) {
          // Multi-threaded: submit a job to run
          final String fileToSubmit = file;
          exec.submit(() -> {
            try {
              processDocument(pipeline, file, IOUtils.slurpFile(new File(fileToSubmit)));
            } catch (Throwable t) {
              t.printStackTrace();
              exceptionCount.incrementAndGet();
            }
          });
        } else {
          // Single-threaded: just run the job
          processDocument(pipeline, file, IOUtils.slurpFile(new File(file)));
        }
      }
    }

    // Exit
    exec.shutdown();
    System.err.println("All files have been queued; awaiting termination...");
    exec.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
    System.err.println("DONE processing files. " + exceptionCount.get() + " exceptions encountered.");
    System.exit(exceptionCount.get());
  }
}
