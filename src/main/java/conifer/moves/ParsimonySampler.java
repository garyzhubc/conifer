package conifer.moves;

import java.util.List;
import java.util.Random;

import conifer.Parsimony;
import blang.factors.Factor;
import blang.mcmc.ConnectedFactor;
import blang.mcmc.MHProposalDistribution;
import blang.mcmc.SampledVariable;

public class ParsimonySampler implements MHProposalDistribution
{
    @SampledVariable Parsimony parsimony; 
    
    @ConnectedFactor List<Factor> factors;
    
    @Override
    public Proposal propose(Random rand)
    {
         return new ProposalRealization();
    }
    
    private class ProposalRealization implements Proposal
    {
        @Override
        public double logProposalRatio() // not symmetric, but Gibbs? 
        {
            return 0;
        }

        // This is a Gibbs move
        @Override
        public void acceptReject(boolean accept){}
        
    }
    
}
