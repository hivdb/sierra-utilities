package edu.stanford.hivdb.sequences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import edu.stanford.hivdb.mutations.FrameShift;
import edu.stanford.hivdb.mutations.Mutation;
import edu.stanford.hivdb.mutations.CodonMutation;
import edu.stanford.hivdb.viruses.Gene;
import edu.stanford.hivdb.viruses.Strain;
import edu.stanford.hivdb.viruses.Virus;

public interface Aligner<VirusT extends Virus<VirusT>> {

	public final static Map<String, Aligner<?>> singletons = new HashMap<>(); 

	public static class MisAlignedException extends IllegalArgumentException {
		/**
		 *
		 */
		private static final long serialVersionUID = 46495128315347L;
		private final boolean suppressible;

		public MisAlignedException(String message, boolean suppressible) {
			super(message);
			this.suppressible = suppressible;
		}

		public boolean isSuppressible() { return suppressible; }
	}

	
	@SuppressWarnings("unchecked")
	public static <AlignerT extends Aligner<VirusT>, VirusT extends Virus<VirusT>> AlignerT getInstance(VirusT virusIns) {
		String className = virusIns.getClass().getName();
		if (!singletons.containsKey(className)) {
			AlignmentConfig<VirusT> alignConfig = virusIns.getAlignmentConfig();
			switch(alignConfig.getMethod()) {
			case PostAlign:
				singletons.put(className, new PostAlignAligner<>(virusIns));
				break;
			default:
				throw new NullPointerException("alignmentConfig.method can not be null");
			}
		}
		return (AlignerT) singletons.get(className);
	}

	/**
	 * @return current virus instance.
	 */
	public VirusT getVirusInstance();
	
	/**
	 * Receives a sequence and aligns it to each gene by NucAmino.
	 *
	 * @param sequence	Sequence waiting to be aligned.
	 * @return 			an AlignedSequence object
	 */
	public default AlignedSequence<VirusT> align(Sequence sequence) {
		List<Sequence> seqs = new ArrayList<>();
		seqs.add(sequence);
		List<AlignedSequence<VirusT>> result = parallelAlign(seqs);
		if (result.isEmpty()) {
			return null;
		}
		return result.get(0);
	}

	/**
	 * Receives set of sequences and aligns them to each gene in parallel.
	 *
	 * @param sequences		Sequence list waiting to be aligned
	 * @return 				list of AlignedSequence objects
	 */
	public default List<AlignedSequence<VirusT>> parallelAlign(List<Sequence> sequences) {
		return parallelAlign(sequences, false);
	}

	/**
	 * Receives set of sequences and aligns them to each gene in parallel.
	 * This method also accept a second parameter to indicate if the input sequence
	 * should be converted to reverse compliment or not.
	 * 
	 * @param sequences           Sequence list to be aligned
	 * @param reversingSequence   flag if the sequence should be converted to reverse compliment
	 * @return                    list of AlignedSequence objects
	 */
	public default List<AlignedSequence<VirusT>> parallelAlign(List<Sequence> sequences, boolean reversingSequence) {
		Map<Sequence, Map<Strain<VirusT>, StringBuilder>> errors = new LinkedHashMap<>();
		List<Sequence> preparedSeqs = sequences;
		if (reversingSequence) {
			preparedSeqs = preparedSeqs.stream()
				.map(s -> s.reverseCompliment())
				.collect(Collectors.toList());
		}

		Map<Sequence, AlignedSequence<VirusT>> results = commandParallelAlign(preparedSeqs, reversingSequence, errors);

		if (!reversingSequence && !errors.isEmpty()) {
			// a second run for reverse complement

			int numStrains = getVirusInstance().getStrains().size();
			List<Sequence> errorSeqs = errors
				.entrySet().stream()
				.filter(e -> e.getValue().size() == numStrains)
				.map(e -> e.getKey())
				.collect(Collectors.toList());
			if (!errorSeqs.isEmpty()) {
				List<AlignedSequence<VirusT>> reversedResults = parallelAlign(errorSeqs, true);
				results = selectBestAlignments(reversedResults, results);
			}
		}
		final Map<Sequence, AlignedSequence<VirusT>> finalResults = results;
		return sequences.stream()
			.map(seq -> finalResults.get(seq))
			.collect(Collectors.toList());
	}

	/**
	 * Command parallel align implementation.
	 *
	 * @param sequences
	 * @param reversingSequence
	 * @param errors
	 * @return
	 */
	public Map<Sequence, AlignedSequence<VirusT>> commandParallelAlign(
		List<Sequence> sequences,
		boolean reversingSequence,
		Map<Sequence, Map<Strain<VirusT>, StringBuilder>> errors
	);

	public default AlignedGeneSeq<VirusT> geneSeqFromReport(
		Sequence sequence,
		Gene<VirusT> gene,
		Map<?, ?> report,
		Double minMatchPcnt,
		final int minNumOfSites
	) {
		int geneLength = gene.getAASize();
		int firstAA = Math.max(1, ((Double) report.get("FirstAA")).intValue());
		int lastAA = Math.min(geneLength, ((Double) report.get("LastAA")).intValue());
		int aaSize = Math.max(0, lastAA - firstAA + 1);
		if (aaSize < minNumOfSites) {
			throw new MisAlignedException(String.format(
				"Alignment of gene %s was discarded " +
				"since the length of alignment was too short (< %d).",
				gene, minNumOfSites
			), aaSize == 0);
		}

		List<?> polAlignedSites = (List<?>) report.get("AlignedSites");
		List<AlignedSite> alignedSites = polAlignedSites.stream()
			.map(m -> (Map<?, ?>) m)
			.map(m -> {
				int posAA = ((Double) m.get("PosAA")).intValue();
				int lengthNA = ((Double) m.get("LengthNA")).intValue();
				List<?> posNAs = (List<?>) m.get("PosNAs");
				if (posNAs == null) {
					// posNAs is not supported. Try posNA
					int posNA = ((Double) m.get("PosNA")).intValue();
					return new AlignedSite(posAA, posNA, lengthNA);
				}
				else {
					return new AlignedSite(
						posAA,
						posNAs.stream()
						.map(pos -> {
							Double typedPos = (Double) pos;
							if (typedPos == null) {
								return null;
							}
							else {
								return typedPos.intValue();
							}
						})
						.collect(Collectors.toList()),
						lengthNA
					);
				}
			})
			.collect(Collectors.toList());

		
		Integer firstNA = null;
		Integer lastNA = null;
		for (AlignedSite site : alignedSites) {
			Optional<Integer> optFirstNA = site.getFirstPosNA();
			if (optFirstNA.isPresent()) {
				firstNA = optFirstNA.get();
				break;
			}
		}
		for (int idx = alignedSites.size() - 1; idx > -1; idx --) {
			AlignedSite site =alignedSites.get(idx); 
			Optional<Integer> optLastNA = site.getLastPosNA();
			if (optLastNA.isPresent()) {
				lastNA = optLastNA.get();
				break;
			}
		}
		if (firstNA == null || lastNA == null) {
			throw new MisAlignedException(String.format(
				"Alignment of gene %s is discarded " +
				"since the List of 'alignedSites' is " +
				"empty or contains only gaps.",
				gene.getAbstractGene()
			), false);
		}
		
		List<?> mutationReports = (List<?>) report.get("Mutations");

		List<Mutation<VirusT>> mutations = mutationReports.stream()
			.map(m -> (Map<?, ?>) m)
			.map(m -> CodonMutation.fromNucAminoMutation(gene, 1, m))
			.collect(Collectors.toList());

		List<?> frameShiftReports = (List<?>) report.get("FrameShifts");
		List<FrameShift<VirusT>> frameShifts = frameShiftReports.stream()
			.map(fs -> (Map<?, ?>) fs)
			.map(fs -> FrameShift.fromNucAminoFrameShift(gene, 1, fs))
			.collect(Collectors.toList());

		int[] trimDels = trimGaps(sequence, firstAA, lastAA, mutations, frameShifts);
		int trimDelsLeft = trimDels[0];
		int trimDelsRight = trimDels[1];

		AlignedGeneSeq<VirusT> geneSeq = new AlignedGeneSeq<>(
			sequence, gene,
			firstAA + trimDelsLeft,
			lastAA - trimDelsRight,
			firstNA + trimDelsLeft * 3,
			lastNA - trimDelsRight * 3,
			alignedSites, mutations, frameShifts);
		if (geneSeq.getMatchPcnt() < minMatchPcnt) {
			throw new MisAlignedException(String.format(
				"Alignment of gene %s is discarded " +
				"since the discordance rate is too high (%.1f%% > %.0f%%).",
				gene.getAbstractGene(), 100 - geneSeq.getMatchPcnt(), 100 - minMatchPcnt
			), false);
		}
		long geneSize = geneSeq.getSize();
		if (geneSize < minNumOfSites) {
			throw new MisAlignedException(String.format(
				"Alignment of gene %s was discarded " +
				"since the length of alignment (%d) was too short (< %d).",
				gene.getAbstractGene(), geneSize, minNumOfSites
			), false);
		}

		return geneSeq;
	}

	/**
	 * Remove only deletion/NNNs from the beginning and the end of alignment
	 *
	 * @param sequence
	 * @param firstAA
	 * @param lastAA
	 * @param mutations
	 * @param frameShifts
	 * @return
	 */
	public default int[] trimGaps(
		Sequence sequence,
		int firstAA,
		int lastAA,
		Collection<Mutation<VirusT>> mutations,
		Collection<FrameShift<VirusT>> frameShifts
	) {
		int trimLeft = 0;
		int trimRight = 0;
		int proteinSize = lastAA - firstAA + 1;
		List<Boolean> gapSites = new ArrayList<>(Collections.nCopies(proteinSize, false));
		for (Mutation<VirusT> mut : mutations) {
			int idx = mut.getPosition() - firstAA;
			if (mut.isDeletion() || mut.isUnsequenced()) {
				gapSites.set(idx, true);
			}
		}
		// remove initial deletions
		for (int idx=0; idx < proteinSize; idx ++) {
			if (!gapSites.get(idx)) {
				if (idx > trimLeft) {
					trimLeft = idx;
				}
				break;
			}
		}
		// remove trailing deletions
		for (int idx=proteinSize-1; idx > -1; idx --) {
			if (!gapSites.get(idx)) {
				if (proteinSize - idx - 1 > trimRight) {
					trimRight = proteinSize - idx - 1;
				}
				break;
			}
		}
		return new int[]{trimLeft, trimRight};
	}

	public default Map<Sequence, AlignedSequence<VirusT>> selectBestAlignments(
		List<AlignedSequence<VirusT>> newAlignments,
		Map<Sequence, AlignedSequence<VirusT>> knownAlignments
	) {
		for (AlignedSequence<VirusT> alignedSeq : newAlignments) {
			if (alignedSeq == null) {
				continue;
			}
			Sequence inputSeq = alignedSeq.getInputSequence();
			
			if (knownAlignments.containsKey(inputSeq)) {
				if (alignedSeq.isEmpty()) {
					// no overwrite
					continue;
				}
				AlignedSequence<VirusT> knownAlignedSeq = knownAlignments.get(inputSeq);
				// if (knownAlignedSeq.getAvailableGenes().size() < alignedSeq.getAvailableGenes().size()) {
				// 	knownAlignments.put(inputSeq, alignedSeq);
				// }
				if (knownAlignedSeq.getNumMatchedNAs() < alignedSeq.getNumMatchedNAs()) {
					knownAlignments.put(inputSeq, alignedSeq);
				}
			}
			else {
				knownAlignments.put(inputSeq, alignedSeq);
			}
		}
		return knownAlignments;
	}


	
}
