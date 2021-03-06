package conifer.models;


import java.util.*;

import conifer.ctmc.*;
import conifer.ctmc.expfam.CTMCExpFam;
import conifer.ctmc.expfam.CTMCState;
import conifer.ctmc.expfam.ExpectedStatistics;
import org.apache.commons.lang3.tuple.Pair;
import org.ejml.simple.SimpleMatrix;
import org.jblas.DoubleMatrix;
import org.jgrapht.UndirectedGraph;

import bayonet.distributions.Multinomial;
import bayonet.graphs.GraphUtils;
import bayonet.marginal.DiscreteFactorGraph;
import bayonet.marginal.FactorGraph;
import bayonet.marginal.Sampler;
import bayonet.marginal.UnaryFactor;
import bayonet.marginal.algo.ExactSampler;
import bayonet.marginal.algo.SumProduct;
import bayonet.math.NumericalUtils;
import briefj.BriefMath;
import briefj.collections.Counter;
import briefj.collections.UnorderedPair;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import conifer.EvolutionaryModel;
import conifer.TopologyUtils;
import conifer.TreeNode;
import conifer.UnrootedTree;
import conifer.io.TreeObservations;


/**
 * A substitution-only phylogenetic likelihood model where each 
 * site can have an independent latent variable determining which
 * of a finite collection of rate matrices to use.
 *
 * This includes multiple rate models such as Yang, J. Mol Evo 94,
 * but can also be more general.
 *
 * This also supports a transition matrix at each leaf to model 
 * for example measurement error models, or more general setups where
 * the characters evolving along the topology come from a different
 * space than the space of observations (e.g. covarion models).
 *
 * @author Alexandre Bouchard (alexandre.bouchard@gmail.com)
 *
 */
public class MultiCategorySubstitutionModel<T extends RateMatrixMixture> implements EvolutionaryModel
{
   
    public final T rateMatrixMixture;

    public final int nSites;

    public MultiCategorySubstitutionModel(T rateMatrixMixture, int nSites)
    {
        this.rateMatrixMixture = rateMatrixMixture;
        this.nSites = nSites;
    }

    /**
     *
     * @param rand
     * @param observations
     * @param tree
     * @return list of statistics, one for each category
     */
    public List<PathStatistics> samplePosteriorPaths(
            Random rand,
            TreeObservations observations,
            UnrootedTree tree)
    {
        return samplePosteriorPaths(rand, observations, tree, TopologyUtils.arbitraryNode(tree), null);
    }

    /**
     *
     * @param rand
     * @param observations
     * @param tree
     * @param root
     * @param paths Modified in place: full information on sampled path written in this datastructure.
     * @return list of statistics, one for each category
     */
    public List<PathStatistics> samplePosteriorPaths(
            Random rand,
            TreeObservations observations,
            UnrootedTree tree,
            TreeNode root,
            TreePath paths)
    {
        List<PathStatistics> categorySpecificStats = Lists.newArrayList();
        for (int cat = 0; cat < nCategories(); cat++)
            categorySpecificStats.add(new PathStatistics(rateMatrixMixture.getRateMatrix(cat).getRateMatrix().length));

        // sample posterior internal reconstructions and indicators
        MultiCategoryInternalNodeSample internalSample = samplePosteriorInternalNodes(rand, observations, tree, root);

        // increment initial distributions
        for (int site = 0; site < nSites; site++)
            categorySpecificStats.get(internalSample.categoryIndicators[site]).addInitial(internalSample.getInternalState(root, site));

        // loop over edges, sites; sample paths using end-point conditioning
        List<EndPointSampler> endPointSamplers = endPointSamplers();


        for (Pair<TreeNode,TreeNode> edge : tree.getRootedEdges(root))
        {
            final TreeNode
                    topNode = edge.getLeft(),
                    botNode = edge.getRight();
            final double branchLength = tree.getBranchLength(topNode, botNode);
            for (int s = 0; s < nSites; s++)
            {
                final int
                        topState = internalSample.getInternalState(topNode, s),
                        botState = internalSample.getInternalState(botNode, s);
                int category = internalSample.categoryIndicators[s];
                Path curPath = (paths == null ? null : paths.createPath(topNode, botNode, s));
                endPointSamplers.get(category).sample(rand, topState, botState, branchLength, categorySpecificStats.get(internalSample.categoryIndicators[s]), curPath);
            }
        }

        return categorySpecificStats;
    }

    public List<PoissonAuxiliarySample> samplePoissonAuxiliaryVariables(Random rand, TreeObservations observations,
                                                                        UnrootedTree tree)
    {
        return samplePoissonAuxiliaryVariables(rand, observations, tree, TopologyUtils.arbitraryNode(tree));
    }

    public List<PoissonAuxiliarySample> samplePoissonAuxiliaryVariables(Random rand, TreeObservations observations,
                                                                        UnrootedTree tree,
                                                                        TreeNode root)
    {
        List<EndPointSampler> endPointSamplers = endPointSamplers();

        // sample posterior internal reconstructions and indicators
        MultiCategoryInternalNodeSample internalSample = samplePosteriorInternalNodes(rand, observations, tree, root);

        List<PoissonAuxiliarySample> result = Lists.newArrayList();
        for (int cat = 0; cat < nCategories(); cat++)
            result.add(new PoissonAuxiliarySample(endPointSamplers.get(cat).maxDepartureRate));

        for (Pair<TreeNode,TreeNode> edge : tree.getRootedEdges(root))
        {
            final TreeNode
                    topNode = edge.getLeft(),
                    botNode = edge.getRight();
            final double branchLength = tree.getBranchLength(topNode, botNode);
            for (int s = 0; s < nSites; s++)
            {
                final int
                        topState = internalSample.getInternalState(topNode, s),
                        botState = internalSample.getInternalState(botNode, s);
                int category = internalSample.categoryIndicators[s];
                int nTransitions = endPointSamplers.get(category).sampleNTransitions(rand, topState, botState, branchLength);
                result.get(category).incrementCount(topNode, botNode, nTransitions);
            }
        }

        return result;
    }

    /**
     * Each instance of PoissonAuxiliarySample holds the sufficient
     * statistics for one category for performing global branch resampling.
     * @author Alexandre Bouchard (alexandre.bouchard@gmail.com)
     *
     */
    public static class PoissonAuxiliarySample
    {
        public final double rate;
        private final Counter<UnorderedPair<TreeNode, TreeNode>>
                transitionCounts = new Counter<UnorderedPair<TreeNode, TreeNode>>(),
                sampleCounts = new Counter<UnorderedPair<TreeNode, TreeNode>>();
        private PoissonAuxiliarySample(double rate)
        {
            this.rate = rate;
        }
        public void incrementCount(TreeNode topNode, TreeNode botNode, int increment)
        {
            transitionCounts.incrementCount(UnorderedPair.of(topNode, botNode), increment);
            sampleCounts.incrementCount(UnorderedPair.of(topNode, botNode), 1.0);
        }
        /**
         *
         * @param topNode
         * @param botNode
         * @return The number of Poisson events for this category.
         */
        public int getTransitionCount(TreeNode topNode, TreeNode botNode)
        {
            return BriefMath.getAndCheckInt(transitionCounts.getCount(UnorderedPair.of(topNode, botNode)));
        }
        /**
         * @param topNode
         * @param botNode
         * @return The number of times this category was used (should be the same for all nodes)
         */
        public int getSampleCount(TreeNode topNode, TreeNode botNode)
        {
            return BriefMath.getAndCheckInt(sampleCounts.getCount(UnorderedPair.of(topNode, botNode)));
        }
    }

    private List<EndPointSampler> endPointSamplers()
    {
        List<EndPointSampler> processes = Lists.newArrayList();
        for (int cat = 0; cat < nCategories(); cat++)
            processes.add(new EndPointSampler(rateMatrixMixture.getRateMatrix(cat).getProcess()));
        return processes;
    }

    public int nCategories()
    {
        return rateMatrixMixture.getLogPriorProbabilities().size();
    }

    @Override
    public int nFactorGraphs()
    {
        return RateMatrixMixtureUtils.nCategories(rateMatrixMixture);
    }

    @Override
    public FactorGraph<TreeNode> newFactorGraph(
            UndirectedGraph<TreeNode, UnorderedPair<TreeNode, TreeNode>> undirectedGraph)
    {
        return new DiscreteFactorGraph<TreeNode>(undirectedGraph);
    }

    @Override
    public void buildObservation(TreeNode leaf, LikelihoodFactoryContext context)
    {
        final int categoryIndex = context.getFactorGraphIndex();
        double [][] observation = (double[][]) context.getObservations().get(leaf);
        UnaryFactor<TreeNode> observationUnary = DiscreteFactorGraph.createUnary(observation);
        RateMatrixToEmissionModel emissionModel = rateMatrixMixture.getRateMatrix(categoryIndex).getEmissionModel();
        if (emissionModel != null)
        {
            double [][] latent2Observation = emissionModel.getMatrixStatesToObservationProbabilities();
            observationUnary = context.getDiscreteFactorGraph().marginalize(new SimpleMatrix(latent2Observation), observationUnary);
        }
        context.getDiscreteFactorGraph().unaryTimesEqual(leaf, observationUnary);
    }

    @Override
    public void buildTransition(TreeNode parent, TreeNode children,
                                LikelihoodFactoryContext context)
    {
        final int categoryIndex = context.getFactorGraphIndex();
        CTMC ctmc = getCTMC(context.getCache(), categoryIndex);
        double branchLength = context.getBranchLength(parent, children);
        double [][] transitionMatrix = ctmc.marginalTransitionProbability(branchLength);
        context.getDiscreteFactorGraph().setBinary(parent, children, new SimpleMatrix(transitionMatrix));
    }

    private final Object CTMC_KEY = new Object();
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private CTMC getCTMC(Map cache, int categoryIndex)
    {
        if (cache.containsKey(CTMC_KEY))
            return (CTMC) cache.get(CTMC_KEY);
        CTMC result = rateMatrixMixture.getRateMatrix(categoryIndex).getProcess(); //new CTMC(rateMatrixMixture.getRateMatrix(categoryIndex));
        cache.put(CTMC_KEY, result);
        return result;
    }

    @Override
    public void buildInitialDistribution(TreeNode node,
                                         LikelihoodFactoryContext context)
    {
        final int categoryIndex = context.getFactorGraphIndex();
        CTMC ctmc = getCTMC(context.getCache(), categoryIndex);
        double [] stationary = ctmc.stationaryDistribution();
        double [][] allStatios = new double[nSites][stationary.length];
        for (int siteIndex = 0; siteIndex < nSites; siteIndex++)
            allStatios[siteIndex] = stationary;
        context.getDiscreteFactorGraph().unaryTimesEqual(node, new SimpleMatrix(allStatios));
    }

    @Override
    public double computeLogLikelihood(LikelihoodComputationContext context)
    {
        // Note: not particularly efficient: lots of logs and array accessed in bad ways,
        // but this occurs only at one point of the tree, so should not be a huge bottleneck in large trees
        // (but could be when the number of sites is large; in which case we could rewrite this with scalings)
        List<UnaryFactor<TreeNode>> rootMarginals = context.getRootMarginals();
        final int nCat = nCategories();
        List<Double> categoryPriorLogPrs = rateMatrixMixture.getLogPriorProbabilities();
        double[][] categoryAndSiteSpecificLikelihoods = categoryAndSiteSpecificLikelihoods(rootMarginals);
        final int nSites = categoryAndSiteSpecificLikelihoods[0].length;
        final double [] workArray = new double[nCat];
        double sum = 0.0;
        for (int s = 0; s < nSites; s++)
        {
            for (int c = 0; c < nCat; c++)
                workArray[c] = categoryAndSiteSpecificLikelihoods[c][s] + categoryPriorLogPrs.get(c);
            sum += NumericalUtils.logAdd(workArray);
        }
        return sum;
    }

    /**
     * Warning: slightly unusual order: category -> site
     * @param rootMarginals
     * @return
     */
    private double [][] categoryAndSiteSpecificLikelihoods(List<UnaryFactor<TreeNode>> rootMarginals)
    {
        double[][] categoryAndSiteSpecificLikelihoods = new double[nCategories()][];
        for (int c = 0; c < rootMarginals.size(); c++)
            categoryAndSiteSpecificLikelihoods[c] = DiscreteFactorGraph.siteLogNormalizations(rootMarginals.get(c));
        return categoryAndSiteSpecificLikelihoods;
    }

    @Override
    public void generateObservationsInPlace(
            Random rand,
            TreeObservations destination,
            UnrootedTree tree, TreeNode root)
    {
        MultiCategoryInternalNodeSample reconstructions = samplePriorInternalNodes(rand, tree, root);
        for (TreeNode leaf : GraphUtils.leaves(tree.getTopology()))
            destination.set(leaf, reconstructions.internalIndicators.get(leaf));
    }

    public MultiCategoryInternalNodeSample samplePosteriorInternalNodes(
            Random rand,
            TreeObservations observations,
            UnrootedTree tree,
            TreeNode root)
    {
        return sampleInternal(rand, false, observations, tree, root);
    }

    public MultiCategoryInternalNodeSample samplePriorInternalNodes(
            Random rand,
            UnrootedTree tree,
            TreeNode root)
    {
        return sampleInternal(rand, true, null, tree, root);
    }

    /**
     *
     * zhaott0416@gmail.com
     * @param tree
     * @return a list of hashmap, the key of the hashmap is the branch length of each edge of the tree and the values is Expm(Qt),
     * the number of elements in the list is the number of categories of the rate factor.
     */

    public List<Map<Double, double [][]>> getExpmRateMtxScaledByBrEachCategory(UnrootedTree tree){

        Collection<Double> allBr =  tree.getBranchLengths().values();
        List<Map<Double, double [][]>> brExpmRateMtx = Lists.newArrayList();
        for(int i=0; i< nCategories(); i++){

            double [][] curRateMtx = rateMatrixMixture.getRateMatrix(i).getRateMatrix();
            Map<Double, double [][]> rateMtxBrMap = new HashMap<Double, double [][]>();
            for(Double br : allBr)
            {
                double [][] rateMtxScaled= curRateMtx.clone();
                double [][] expRateMtxScaled = RateMatrixUtils.marginalTransitionMtx(rateMtxScaled, br);
                rateMtxBrMap.put(br,expRateMtxScaled);
            }

            brExpmRateMtx.add(i, rateMtxBrMap);
        }

        return brExpmRateMtx;
    }

    public  List<Map< Pair<TreeNode, TreeNode>, double [][]>> getMarginalCount(TreeObservations observations, UnrootedTree tree)
    {
        return getMarginalCount(observations, tree, TopologyUtils.arbitraryNode(tree));
    }

    public List<Map< Pair<TreeNode, TreeNode>, double [][]>> getMarginalCount(TreeObservations observations, UnrootedTree tree, TreeNode root)
    {

        List<FactorGraph<TreeNode>> factorGraphs = EvolutionaryModelUtils.buildFactorGraphs(this, tree, root, observations);
        int nSites = -1;
        List<SumProduct<TreeNode>> sumProds = Lists.newArrayList();
        List<Map<Pair<TreeNode, TreeNode>, double [][]>> marginalCountAllCategory = Lists.newArrayList();
        List<Map<Double, double [][]>>  listExpmRateMtxScaledByBranch = getExpmRateMtxScaledByBrEachCategory(tree);
        for(int nCat=0; nCat<factorGraphs.size(); nCat++) {
            FactorGraph<TreeNode> factorGraph = factorGraphs.get(nCat);
            SumProduct<TreeNode> sumProd = new SumProduct<TreeNode>(factorGraph);
            sumProds.add(sumProd);
            int nStates = rateMatrixMixture.getRateMatrix(0).getRateMatrix().length;

            Map<Pair<TreeNode, TreeNode>, double[][]> marginalCountSingleBranch = new HashMap<Pair<TreeNode, TreeNode>, double[][]>();
            for (Pair<TreeNode, TreeNode> edge : tree.getRootedEdges(root)) {
                final TreeNode
                        topNode = edge.getLeft(),
                        botNode = edge.getRight();
                final double branchLength = tree.getBranchLength(topNode, botNode);
                double[][] marginalCountArray = new double[nStates][nStates];
                DoubleMatrix marginalCountMtx = new DoubleMatrix(marginalCountArray);
                double[][] p1TopNode = DiscreteFactorGraph.getNormalizedCopy(sumProd.computeMarginal(topNode));
                double[][] p1botNode = DiscreteFactorGraph.getNormalizedCopy(sumProd.computeMarginal(botNode));
                double[][] msgBotNode = DiscreteFactorGraph.getNormalizedCopy(sumProd.getMessage(topNode, botNode));
                double[][] msgTopNode = DiscreteFactorGraph.getNormalizedCopy(sumProd.getMessage(botNode, topNode));
                double [][] transitionProb = listExpmRateMtxScaledByBranch.get(nCat).get(branchLength);

                nSites = p1TopNode.length;
                for (int i = 0; i < nSites; i++) {
                    double [][] unNormalizedMarginalCount= new double[nStates][nStates];
                    double sum =0;
                    for (int j = 0; j < nStates; j++) {
                        for (int k = 0; k < nStates; k++) {

                            unNormalizedMarginalCount[j][k] = p1TopNode[i][j] * p1botNode[i][k]*transitionProb[j][k] / (msgTopNode[i][j] * msgBotNode[i][k]);
                            sum += unNormalizedMarginalCount[j][k];
                        }

                    }

                    DoubleMatrix normalizedMarginalCountArraySingleSite = new DoubleMatrix(unNormalizedMarginalCount).div(sum);
                    marginalCountMtx.addi(normalizedMarginalCountArraySingleSite);


                }
                marginalCountArray = marginalCountMtx.toArray2();
                marginalCountSingleBranch.put(edge, marginalCountArray);

            }

            marginalCountAllCategory.add(marginalCountSingleBranch);
        }
        return marginalCountAllCategory;

    }

    /*
     * TODO: move the stuff below into ExpFam
     */
    
    public ExpectedStatistics<CTMCState> getTotalExpectedStatistics(TreeObservations observations, UnrootedTree tree, CTMCExpFam<CTMCState> model) 
    {
        return getTotalExpectedStatistics(observations, tree, TopologyUtils.arbitraryNode(tree), model);
    }

    public ExpectedStatistics<CTMCState> getTotalExpectedStatistics(TreeObservations observations, UnrootedTree tree, TreeNode root,
                                                                    CTMCExpFam<CTMCState> model)
    {

        List<Map< Pair<TreeNode, TreeNode>, double [][]>> totalMarginalCount = getMarginalCount(observations, tree);
        ExpectedStatistics<CTMCState> result = new ExpectedStatistics<CTMCState>(model);
        for(int i=0; i< nCategories(); i++){
            for (Pair<TreeNode, TreeNode> edge : tree.getRootedEdges(root)) {
                final TreeNode
                        topNode = edge.getLeft(),
                        botNode = edge.getRight();
                final double branchLength = tree.getBranchLength(topNode, botNode);

                if(topNode.equals(root))
                    result.nInit = new DoubleMatrix(totalMarginalCount.get(i).get(edge)).rowSums().toArray();

                result.addMarginalizedPath(totalMarginalCount.get(i).get(edge),  rateMatrixMixture.getRateMatrix(i).getRateMatrix(), branchLength);
            }

            //EigenCTMC ctmc = new EigenCTMC(rateMatrixMixture.getRateMatrix(i).getRateMatrix());
            //double [] stationaryDist = ctmc.stationaryDistribution();
            //result.nInit = new DoubleMatrix(stationaryDist).mul(nSites).toArray();
            //result.nInit = result.holdTimes;

        }

        return result;

    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private MultiCategoryInternalNodeSample sampleInternal(
            Random rand,
            boolean isPrior,
            TreeObservations observations,
            UnrootedTree tree,
            TreeNode root)
    {
        // sample full paths for all categories (a bit wasteful, but not more costly than doing posterior inference)
        List<FactorGraph<TreeNode>> factorGraphs = EvolutionaryModelUtils.buildFactorGraphs(this, tree, root, observations);
        List<Map<TreeNode, double[][]>> allSamples = Lists.newArrayList();
        int nSites = -1;
        List<SumProduct<TreeNode>> sumProds = Lists.newArrayList();
        for (FactorGraph<TreeNode> factorGraph : factorGraphs)
        {
            Sampler<TreeNode> innerSampler = ((DiscreteFactorGraph)factorGraph).getSampler();
            SumProduct<TreeNode> sumProd = isPrior ? null : new SumProduct<TreeNode>(factorGraph);
            sumProds.add(sumProd);
            ExactSampler<TreeNode> sampler = isPrior ?
                    ExactSampler.priorSampler(factorGraph, innerSampler) :
                    ExactSampler.posteriorSampler(sumProd, innerSampler);
            Map<TreeNode, UnaryFactor<TreeNode>> samples = sampler.sample(rand, root);
            Map<TreeNode, double[][]> transformedSamples = Maps.newHashMap();
            for (TreeNode leaf : tree.getTopology().vertexSet())
            {
                double [][] current = DiscreteFactorGraph.getNormalizedCopy(samples.get(leaf));
                nSites = current.length;
                transformedSamples.put(leaf, current);
            }
            allSamples.add(transformedSamples);
        }
        // sample category indicators
        final int nCat = factorGraphs.size();
        int [] indicators = new int[nSites];
        List<Double> categoryPriorLogPrs = rateMatrixMixture.getLogPriorProbabilities();
        double [][] categoryAndSiteSpecificLikelihoods = isPrior ?
                null :
                categoryAndSiteSpecificLikelihoods(EvolutionaryModelUtils.getRootMarginals(sumProds, root));
        // convert into a better data structure organization
        final double [] workArray = new double[nCat];
        for (int s = 0; s < nSites; s++)
        {
            for (int c = 0; c < nCat; c++)
                workArray[c] =  categoryPriorLogPrs.get(c) + (isPrior ? 0.0 : categoryAndSiteSpecificLikelihoods[c][s]);
            Multinomial.expNormalize(workArray);
            indicators[s] = Multinomial.sampleMultinomial(rand, workArray);
        }
        Map<TreeNode, double[][]> reconstructions = Maps.newHashMap();
        for (TreeNode leaf : tree.getTopology().vertexSet())
        {
            double [][][] allSamplesTransformed = new double[nCat][][]; // cat -> site -> state
            for (int cat = 0; cat < nCat; cat++)
                allSamplesTransformed[cat] = allSamples.get(cat).get(leaf);

            double [][] result = new double[nSites][]; // site -> state
            for (int site = 0; site < nSites; site++)
                result[site] = allSamplesTransformed[indicators[site]][site];

            reconstructions.put(leaf, result);
        }
        return new MultiCategoryInternalNodeSample(indicators, reconstructions);
    }

    public static class MultiCategoryInternalNodeSample
    {

        /**
         * Indexed by site. Entry categoryIndicators[s] gives the
         * category sampled at site s.
         */
        public final int [] categoryIndicators;

        /**
         * For any internal node n, site s, state x,
         * reconstruction.get(n)[s][x] is equal to one if x is the
         * sample state at that node and site, and zero otherwise.
         */
        private final Map<TreeNode, double[][]> internalIndicators;

        public int getInternalState(TreeNode n, int site)
        {
            double [] array = internalIndicators.get(n)[site];
            boolean found = false;
            int result = -1;
            for (int i = 0; i < array.length; i++)
                if (array[i] == 1.0)
                {
                    if (found) throw new RuntimeException();
                    found = true;
                    result = i;
                }
            return result;
        }

        private MultiCategoryInternalNodeSample(int[] categoryIndicators,
                                                Map<TreeNode, double[][]> reconstructions)
        {
            this.categoryIndicators = categoryIndicators;
            this.internalIndicators = reconstructions;
        }
    }

    /**
     * Evolutionary paths along a tree and assuming a multi-category substitution model.
     *
     * @author Alexandre Bouchard (alexandre.bouchard@gmail.com)
     *
     */
    public static class TreePath
    {
        private final UnrootedTree tree;
        private final TreeNode root;
        /**
         * site -> edge (pair) -> path
         */
        private final List<Map<Pair<TreeNode,TreeNode>, Path>> pathSegments;

        public TreePath(UnrootedTree tree, TreeNode root,
                        int nSites)
        {
            this.tree = tree;
            this.root = root;
            this.pathSegments = Lists.newArrayList();
            for (int i = 0; i < nSites; i++)
                pathSegments.add(new HashMap<Pair<TreeNode,TreeNode>, Path>());
        }

        public Path getPath(TreeNode topNode, TreeNode botNode, int site)
        {
            return pathSegments.get(site).get(Pair.of(topNode, botNode));
        }

        public Path createPath(TreeNode topNode, TreeNode botNode, int site)
        {
            Path result = new Path();
            pathSegments.get(site).put(Pair.of(topNode, botNode), result);
            return result;
        }

        public TreeNode getRoot()
        {
            return root;
        }

        public UnrootedTree getTree()
        {
            return tree;
        }
    }

}