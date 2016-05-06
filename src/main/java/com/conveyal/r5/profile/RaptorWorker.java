package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.BoardingAssumption;
import com.conveyal.r5.publish.StaticPropagatedTimesStore;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.profile.PropagatedTimesStore.ConfidenceCalculationMethod;
import com.conveyal.r5.streets.PointSetTimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.stream.IntStream;

/**
 * This is an exact copy of RaptorWorker that's being modified to work with new TransportNetworks (from R5)
 * instead of (old) OTP Graphs. We can afford the maintainability nightmare of duplicating so much code because
 * we will soon delete the old OTP-based raptor worker class entirely.
 *
 * This class originated as a rewrite of our RAPTOR code that would use "thin workers", allowing
 * computation by a generic function-execution service like AWS Lambda. The gains in efficiency were significant enough
 * that this is now the way we do all analysis work.
 *
 * This system also accounts for pure-frequency routes by using Monte Carlo methods (generating randomized schedules).
 *
 * This implements the RAPTOR algorithm; see http://research.microsoft.com/pubs/156567/raptor_alenex.pdf
 */
public class RaptorWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RaptorWorker.class);
    public static final int UNREACHED = Integer.MAX_VALUE;
    static final int MAX_DURATION = Integer.MAX_VALUE - 48 * 60 * 60;

    /** Minimum slack time to board transit in seconds. */
    public static final int BOARD_SLACK_SECONDS = 60;

    /** How much to decrease the departure time between scheduled search iterations. */
    private static final int DEPARTURE_STEP_SEC = 60;

    int max_time = 0;
    int round = 0;
    private int scheduledRounds = -1;

    /**
     * One {@link RaptorState} per round of the scheduled search. We don't need to keep a separate RaptorState at each
     * departure minute because we use range-raptor on the scheduled search. We step backward through the departure
     * minutes and reuse the RaptorStates.
     *
     * We need to have a separate state for each round to prevent ridiculous paths from being taken where multiple
     * vehicles are ridden in the same round (see issue #23)
     *
     * Suppose lines are arranged in the graph in order 1...n, and line 2 goes towards the destination and 1 away
     * from it. The RAPTOR search will first encounter line 1 and ride it away from the destination. Then it will
     * board line 2 (in the same round) and ride it towards the destination. If frequencies are low enough, it will
     * catch the same trip it would otherwise have caught by boarding at the origin, so will not re-board at the
     * origin, hence the crazy path (which is still optimal in the earliest-arrival sense). This is also why some
     * paths have a huge tangle of routes, because the router is finding different ways to kill time somewhere along
     * the route before catching the bus that will take you to the destination.
     */
    List<RaptorState> scheduleState;

    TransitLayer data;

    /** Patterns touched during this round. */
    BitSet patternsTouchedThisRound;

    /** Stops touched during this round. */
    BitSet stopsTouchedThisRound;

    /**
     * Stops touched during this search (with "search" meaning either a scheduled search at a particular minute,
     * or one particular draw within a departure minute when working with frequency routes).
     * Used in propagating travel times out to targets.
     * Cleared before each search; in order for propagation to be correct, doPropagation must be called after every
     * minute of the scheduled search, and after each search for a frequency draw. The propagation output table for
     * the scheduled search at minute M is saved and reused in the scheduled search for minute M-1.
     * The propagation output table for a scheduled search is also copied into each of the frequency searches within
     * that same minute, so we only need to do propagation from stops that have been touched in a particular search,
     * and update the tables.
     */
    BitSet stopsTouchedThisSearch;

    private ProfileRequest req;

    /** Clock time spent on propagation, for display and debugging. */
    private long totalPropagationTime = 0;

    private FrequencyRandomOffsets offsets;

    public PropagatedTimesStore propagatedTimesStore;

    public LinkedPointSet targets;

    public BitSet servicesActive;

    /**
     * After each iteration (search at a single departure time with a specific Monte Carlo draw of schedules for
     * frequency routes) we store the {@link RaptorState}. If null, states will not be saved (they are not needed
     * in analysis and chew gobs of memory, especially when monteCarloDraws is large).
     */
    public List<RaptorState> statesEachIteration = null;

    /**
     * After each iteration (search at a single departure time with a specific Monte Carlo draw of schedules for
     * frequency routes) we store travel time to every target. (or arrival time? TODO clarify)
     */
    public int[][] timesAtTargetsEachIteration;

    /**
     * Whether each iteration (departure time / frequency draw) should be included in averages.
     * The frequency searches that represent lower and upper bounds rather than randomly selected schedules
     * should not be included in the averages.
     */
    public BitSet includeInAverages = new BitSet();

    public RaptorWorker (TransitLayer data, LinkedPointSet targets, ProfileRequest req) {
        this.data = data;
        int nStops = data.streetVertexForStop.size();

        stopsTouchedThisRound = new BitSet(nStops);
        patternsTouchedThisRound = new BitSet(data.tripPatterns.size());
        stopsTouchedThisSearch = new BitSet(nStops);
        this.scheduleState = new ArrayList<>();
        this.scheduleState.add(new RaptorState(nStops));

        this.targets = targets;

        // targets== null implies a static site, save the results of each iteration
        if (targets == null) this.statesEachIteration = new ArrayList<>();

        this.servicesActive = data.getActiveServicesForDate(req.date);

        this.req = req.clone();
        offsets = new FrequencyRandomOffsets(data);
    }

    /**
     * Prepare the RaptorWorker for the next RAPTOR round, copying or creating states as needed.
     * If no previous departure minute or frequency draw has reached the next round before, initialize
     * the next round from the current round. This function gets called in both scheduled searches and frequency
     * searches, and the frequency search may proceed for more rounds than the scheduled search did.
     * We always need to initialize the scheduled state here even when running a frequency search, because
     * we copy that scheduled state to initialize each round of the frequency search.
     */
    public void advanceToNextRound() {
        if (scheduleState.size() == round + 1) {
            scheduleState.add(scheduleState.get(round).copy());
        } else {
            // Copy best times forward
            scheduleState.get(round + 1).min(scheduleState.get(round));
        }
        round++;
    }

    /**
     * This is the entry point to kick off a full RAPTOR search over many departure minutes randomizing
     * frequency routes as needed.
     * @param accessTimes a map from transit stops to the time it takes to reach those stops
     * @param nonTransitTimes the time to reach all targets without transit. Targets can be vertices or points/samples.
     */
    public PropagatedTimesStore runRaptor (TIntIntMap accessTimes, PointSetTimes nonTransitTimes, TaskStatistics ts) {
        long beginCalcTime = System.currentTimeMillis();
        TIntIntMap initialStops = new TIntIntHashMap(accessTimes);
        TIntIntIterator initialIterator = accessTimes.iterator();
        // TODO Isn't this just copying an int-int map into another one? Why reimplement map copy?
        // It appears that we could just use accessTimes directly without even copying it.
        while (initialIterator.hasNext()) {
            initialIterator.advance();
            int stopIndex = initialIterator.key();
            int accessTime = initialIterator.value();
            if (accessTime <= 0) {
                LOG.error("access time to stop {} is {}", stopIndex, accessTime);
            }
            initialStops.put(stopIndex, accessTime);
        }

        // We don't propagate travel times from stops out to the targets or street intersections when generating
        // a static site because the Javascript client does the propagation.
        boolean doPropagation = targets != null;

        // In normal usage propagatedTimesStore is null here, but in tests a custom one may have been injected.
        if (propagatedTimesStore == null) {
            if (doPropagation) {
                // Store min, max, avg travel times to all search targets (a set of points of interest in a PointSet)
                propagatedTimesStore = new PropagatedTimesStore(targets.size());
            } else {
                // Store all the travel times (not averages) to all transit stops.
                propagatedTimesStore = new StaticPropagatedTimesStore(data.getStopCount());
            }
        }

        int nTargets = targets != null ? targets.size() : data.getStopCount();

        // If there are no frequency routes, we don't do any Monte Carlo draws within departure minutes.
        // i.e. only a single RAPTOR search is run per departure minute.
        // If we do Monte Carlo, we do more iterations. But we only do Monte Carlo when we have frequencies.

        // First, set the number of iterations to the number of departure minutes.
        int iterations = (req.toTime - req.fromTime - DEPARTURE_STEP_SEC) / DEPARTURE_STEP_SEC + 1;

        // figure out how many monte carlo draws to do (if we end up doing monte carlo).
        // this is defined outside the conditional because it is also used in the loop body.
        // At this point the number of iterations is just the number of minutes.
        int monteCarloDraws = (int) Math.ceil((double) req.monteCarloDraws / iterations);

        // Now multiply the number of departure minutes by the number of Monte Carlo frequency draws per minute.
        if (data.hasFrequencies) {
            // We add 2 because we do two additional iterations for zero and maximal boarding times (not Monte Carlo draws).
            iterations *= (monteCarloDraws + 2);
        }

        ts.searchCount = iterations;

        // We will iterate backward through minutes (range-raptor) taking a snapshot travel times to targets after each iteration.
        timesAtTargetsEachIteration = new int[iterations][nTargets];

        ts.timeStep = DEPARTURE_STEP_SEC;

        // times at targets from scheduled search
        // we keep a single output array with clock times, and range raptor updates it only as needed as the departure
        // minute moves backwards.
        int[] scheduledTimesAtTargets = new int[nTargets];
        Arrays.fill(scheduledTimesAtTargets, UNREACHED);

        int iteration = 0; // The current iteration (over all departure minutes and Monte Carlo draws)
        int minuteNumber = 0; // How many different departure minutes have been hit so far, for display purposes.

        // FIXME this should be changed to tolerate a zero-width time range
        for (int departureTime = req.toTime - DEPARTURE_STEP_SEC; departureTime >= req.fromTime; departureTime -= DEPARTURE_STEP_SEC) {
            if (minuteNumber++ % 15 == 0) {
                LOG.info("minute {}", minuteNumber);
            }

            // Compensate for Java obnoxious policies on "effectively final" variables in closures
            final int departureTimeFinal = departureTime;
            scheduleState.stream().forEach(rs -> rs.departureTime = departureTimeFinal);

            // Run the search on only the scheduled routes (not frequency-based routes) at this departure minute.
            this.runRaptorScheduled(initialStops, departureTime);

            // If we're doing propagation from transit stops out to street vertices, do it now.
            // If we are instead saving travel times to transit stops (not propagating out to the streets)
            // we skip this step -- we'll just copy all the travel times at once after the frequency search.
            if (doPropagation) {
                this.doPropagation(scheduleState.get(round).bestNonTransferTimes, scheduledTimesAtTargets, departureTime);

                // Copy in the travel times on the street network without boarding transit.
                // We don't want to force people to ride transit instead of walking a block.
                for (int i = 0; i < scheduledTimesAtTargets.length; i++) {
                    int nonTransitTravelTime = nonTransitTimes.getTravelTimeToPoint(i);
                    int nonTransitClockTime = nonTransitTravelTime + departureTime;
                    if (nonTransitTravelTime != UNREACHED && nonTransitClockTime < scheduledTimesAtTargets[i]) {
                        scheduledTimesAtTargets[i] = nonTransitClockTime;
                    }
                }
            }

            // Run any searches on frequency-based routes.
            if (data.hasFrequencies) {
                for (int i = 0; i < monteCarloDraws + 2; i++, iteration++) {

                    RaptorState stateCopy;
                    if (i == 0) {
                        stateCopy = this.runRaptorFrequency(departureTime, BoardingAssumption.BEST_CASE);
                    }
                    else if (i == 1) {
                        stateCopy = this.runRaptorFrequency(departureTime, BoardingAssumption.WORST_CASE);
                    }
                    else {
                        offsets.randomize();
                        stateCopy = this.runRaptorFrequency(departureTime, BoardingAssumption.RANDOM);
                        // Only include travel times from randomized schedules (not the lower and upper bounds) in averages.
                        includeInAverages.set(iteration);
                    }

                    // Propagate travel times out to targets.
                    int[] frequencyTimesAtTargets = timesAtTargetsEachIteration[iteration];
                    if (doPropagation) {
                        // copy scheduled times into frequency array so that we don't have to propagate them again, we'll
                        // just update them where they've improved, see #137.
                        System.arraycopy(scheduledTimesAtTargets, 0, frequencyTimesAtTargets, 0,
                                scheduledTimesAtTargets.length);
                        // updates timesAtTargetsEachIteration directly because it has a reference into the array.
                        this.doPropagation(stateCopy.bestNonTransferTimes, frequencyTimesAtTargets,
                                departureTime);
                    } else {
                        // copy times at stops into output (includes frequency and scheduled times because we copied the scheduled state)
                        System.arraycopy(stateCopy.bestNonTransferTimes, 0, frequencyTimesAtTargets, 0, stateCopy.bestNonTransferTimes.length);
                    }

                    if (statesEachIteration != null) statesEachIteration.add(stateCopy.deepCopy());

                    // convert to elapsed time
                    for (int t = 0; t < frequencyTimesAtTargets.length; t++) {
                        if (frequencyTimesAtTargets[t] != UNREACHED)
                            frequencyTimesAtTargets[t] -= departureTime;
                    }
                }
            } else {
                // There were no frequency routes. We did no frequency draws, so propagate the scheduled times instead.
                final int dt = departureTime;
                final RaptorState state = scheduleState.get(round);
                // Either use the propagated result at the targets (if we calculated it) or the travel times at stops.
                // Static sites propagate to the final targets on the client, so we only store travel time to stops.
                timesAtTargetsEachIteration[iteration] = IntStream
                        .of(doPropagation ? scheduledTimesAtTargets : state.bestNonTransferTimes)
                        .map(i -> i != UNREACHED ? i - dt : i)
                        .toArray();
                includeInAverages.set(iteration);
                if (statesEachIteration != null) statesEachIteration.add(state.deepCopy());
                iteration++;
            }
        } // END for loop over departure minutes

        // Sanity check:
        // Ensure that the output arrays were entirely filled.
        // If they are not, this implies a bug in R5 and our results are garbage.
        // This has happened in the past when we did not set the number of iterations correctly.
        // After completion of the above nested loops, iteration should be incremented one past the end of the arrays.
        if (iteration != iterations) {
            throw new IllegalStateException("Iterations did not completely fill output array");
        }

        // Display the time spent calculating all iterations.
        long calcTime = System.currentTimeMillis() - beginCalcTime;
        LOG.info("calc time {}sec", calcTime / 1000.0);
        LOG.info("  propagation {}sec", totalPropagationTime / 1000.0);
        LOG.info("  raptor {}sec", (calcTime - totalPropagationTime) / 1000.0);
        LOG.info("  requested {} monte carlo draws, ran {}", req.monteCarloDraws, monteCarloDraws * minuteNumber);
        LOG.info("{} rounds", round + 1);
        ts.propagation = (int) totalPropagationTime;
        ts.transitSearch = (int) (calcTime - totalPropagationTime);

        // Copy the detailed times (at every target at every iteration) over into the PropagatedTimesStore which
        // summarizes them (average, min, max).
        // We can use the MIN_MAX confidence calculation method here even when frequency draws were done, because we
        // include two pseudo-draws per departure minute for zero and maximal board times.
        propagatedTimesStore.setFromArray(timesAtTargetsEachIteration, includeInAverages, ConfidenceCalculationMethod.MIN_MAX);
        return propagatedTimesStore;
    }

    /**
     * Run a raptor search on all scheduled routes, ignoring any frequency-based routes.
     * The output of this process will be stored in the scheduleState field.
     */
    public void runRaptorScheduled (TIntIntMap initialStops, int departureTime) {

        // Do not consider any travel after this time (in seconds after midnight).
        // FIXME We may generate results past this time, but they are invalid and should not be included in averages.
        // This is currently effectively turned off by making MAX_DURATION very large.
        max_time = departureTime + MAX_DURATION;

        // Clear any information left over from previous searches.
        round = 0;
        patternsTouchedThisRound.clear();
        stopsTouchedThisSearch.clear();
        stopsTouchedThisRound.clear();

        // Copy access times to initial stops over to the first round's state.
        // This is the "zeroth" round. All other rounds contain transit followed by stop-to-stop transfers, but this
        // round has only transfers (i.e. getting to transit on the street network is treated as a transfer).
        // TODO now that we have separate state per round, this could be simpler. Transit can't get you to a stop faster in the 0th round.
        TIntIntIterator iterator = initialStops.iterator();
        while (iterator.hasNext()) {
            iterator.advance();
            int stopIndex = iterator.key();
            int time = iterator.value() + departureTime;
            // note not setting bestNonTransferTimes here because the initial walk is effectively a "transfer"
            RaptorState state = scheduleState.get(0);
            if (time < state.bestTimes[stopIndex]) {
                state.bestTimes[stopIndex] = time;
                // don't clear previousPatterns/previousStops because we want to avoid egressing from the stop at which
                // we boarded, allowing one to blow past the walk limit. See #22.
                state.transferStop[stopIndex] = -1;
                markPatternsForStop(stopIndex);
            }
        }

        // Move on to the round 1, the first one that uses transit.
        advanceToNextRound();

        // Run RAPTOR rounds repeatedly until a round has no effect (does not update the travel time to any stops).
        while (doOneRound(scheduleState.get(round - 1), scheduleState.get(round), false, null)) {
            advanceToNextRound();
        }

        // We must ensure that we do at least one more frequency round than we did scheduled rounds at any minute
        // up to this point. This ensures that we can board a frequency route that can only be reached from a scheduled
        // vehicle found in previous searches with high numbers of rounds. See tickets #80 and #82.
        // We can't determine the largest number of rounds yet encountered by looking at the size of scheduleState
        // during the frequency searches, because empty rounds may be added to the end of scheduleState by the frequency search.
        scheduledRounds = Math.max(round + 1, scheduledRounds);

        // This scheduled search may have taken less rounds than some previously run search at another minute.
        // We want to fill in state for as many rounds as we've ever done up to this point.
        while (round < scheduleState.size() - 1) {
            // Copy the minimum travel time for each stop into the next round,
            // considering this round and existing results for the next round.
            scheduleState.get(round + 1).min(scheduleState.get(round));
            round++;
        }
    }

    /**
     * Run a RAPTOR search using frequencies. Return the resulting state, which is a copy of the state that was left
     * in the scheduleState field by runRaptorScheduled, but with the results of the frequency search included.
     * We make a copy because range-RAPTOR is invalid with frequencies. We are in fact doing range-RAPTOR over all
     * departure minutes using the scheduled route, and we copy those results at each minute to apply the frequency
     * results on top of them.
     */
    public RaptorState runRaptorFrequency (int departureTime, BoardingAssumption boardingAssumption) {

        // Do not consider any travel after this clock time (currently effectively turned off).
        max_time = departureTime + MAX_DURATION;

        round = 0;
        advanceToNextRound(); // go to first round

        // Clear any information left over from previous searches.
        patternsTouchedThisRound.clear();
        stopsTouchedThisSearch.clear();
        stopsTouchedThisRound.clear();

        // Mark only frequency patterns here. Any scheduled patterns that are reached downstream of frequency patterns
        // will be marked during the search and explored in subsequent rounds.
        for (int p = 0; p < data.tripPatterns.size(); p++) {
            TripPattern pat = data.tripPatterns.get(p);
            if (pat.hasFrequencies) {
                patternsTouchedThisRound.set(p);
            }
        }

        // Initialize the search state with the zeroth round from the scheduled search, which contains the access times
        // to stops on the street network. There is no need to make a copy here as this is not updated in the search.
        RaptorState previousRound = scheduleState.get(round - 1);

        // Copy the results of the first ride on a scheduled vehicle, which serve as bounds on the first ride on a frequency vehicle.
        RaptorState currentRound = scheduleState.get(round).copy();
        currentRound.previous = previousRound;

        // Anytime a round updates the travel time to some stops, move on to another round.
        // Do at least as many rounds as were done in the scheduled search plus one, so that we don't return a state
        // at a previous round and cut off the scheduled search after only a few transfers (see issue #82)
        // However, if we didn't run a scheduled search, don't apply this constraint
        while (doOneRound(previousRound, currentRound, true, boardingAssumption) ||
                (scheduledRounds != -1 && round <= scheduledRounds)) {
            advanceToNextRound();
            previousRound = currentRound;
            currentRound = previousRound.copy();

            // Copy the travel times from the scheduled search into this round's state.
            currentRound.min(scheduleState.get(round));

            // If we did a scheduled search, on every round we re-mark all frequency patterns for exploration.
            // They may be boarded at the second, third or later round by a transfer from a scheduled trip.
            if (data.hasSchedules) {
                for (int p = 0; p < data.tripPatterns.size(); p++) {
                    TripPattern pat = data.tripPatterns.get(p);
                    if (pat.hasFrequencies) {
                        patternsTouchedThisRound.set(p);
                    }
                }
            }
        }
        
        return currentRound;
    }

    /**
     * Perform one round of the RAPTOR search. This is used for both scheduled and frequency searches.
     * If useFrequencies is false we're doing a scheduled search and the boardingAssumption is ignored.
     * In that case it may be set to null.
     *
     * Note that scheduled vehicles are always explored, even when we're doing a frequency search.
     * The reverse is not true: on a scheduled search, the frequency routes are ignored.
     *
     * It is possible to transfer between frequency and scheduled service an arbitrary number of times.
     * Therefore the last search that is run must include both scheduled and frequency routes,
     * i.e. we also include any scheduled patterns touched during the frequency search.
     *
     * Searching on the frequency routes separately from the scheduled routes is an optimization in the sense that
     * it enables us to use the range-RAPTOR optimization on the scheduled searches.
     *
     * For more explanation see OpenTripPlanner #2072
     */
    public boolean doOneRound (RaptorState inputState, RaptorState outputState, boolean useFrequencies, BoardingAssumption boardingAssumption) {
        // Clear any stops that were marked in the previous round.
        stopsTouchedThisRound.clear();
        PATTERNS: for (int p = patternsTouchedThisRound.nextSetBit(0); p >= 0; p = patternsTouchedThisRound.nextSetBit(p+1)) {
            TripPattern timetable = data.tripPatterns.get(p);
            // Do not even consider patterns that have no trips on active service IDs.
            // Anecdotally this can double or triple search speed.
            if ( ! timetable.servicesActive.intersects(servicesActive)) {
                continue;
            }
            int stopPositionInPattern = -1; // first increment will land this at zero

            int bestFreqBoardTime = Integer.MAX_VALUE;
            int bestFreqBoardStop = -1;
            int bestFreqBoardStopIndex = -1;

            // This is which _trip_ we are on if we are riding a frequency-based service.
            // It is not important which frequency entry we used to board it.
            TripSchedule bestFreqTrip = null;

            // First look for a frequency entry.
            if (useFrequencies) {
                for (int stopIndex : timetable.stops) {
                    stopPositionInPattern += 1;

                    // The arrival time at this stop if we have already boarded a trip and stay on that same trip.
                    int remainOnBoardTime;
                    if (bestFreqTrip != null) {
                        // We have already boarded a trip, stay on board that trip.
                        remainOnBoardTime = bestFreqBoardTime +
                                (bestFreqTrip.arrivals[stopPositionInPattern] - bestFreqTrip.departures[bestFreqBoardStop]);
                    } else {
                        // We cannot remain on board as we have not yet boarded any trip.
                        remainOnBoardTime = Integer.MAX_VALUE;
                    }

                    // This stop has been reached by some previous round. Try to board a vehicle here.
                    // This could be the first time we board a vehicle on this pattern in this round, but if already
                    // on board a vehicle we also need to check whether it's possible to board an earlier one.
                    // TODO only attempt boarding when we have a non-UNREACHED value from the last round, not based on the bestTimes.
                    // To do this, in the frequency search we'd need to re-mark stops that were reached by the scheduled search in the previous round.
                    // stopsTouched could just be put into RaptorState.
                    if (inputState.bestTimes[stopIndex] != UNREACHED) {
                        for (int tripScheduleIdx = 0; tripScheduleIdx < timetable.tripSchedules.size(); tripScheduleIdx++) {
                            TripSchedule ts = timetable.tripSchedules.get(tripScheduleIdx);
                            // TODO each pattern should be made to contain only scheduled or only frequency trips
                            if (ts.headwaySeconds == null || !servicesActive.get(ts.serviceCode)) {
                                continue; // Not a frequency trip, or not running today.
                            }
                            // Calculate the best board time for each frequency entry on this trip, and choose the best
                            // of those. This is a valid thing to do and doesn't exhibit the problems we've seen in the
                            // past with Monte Carlo simulations where we effectively took the min of several random
                            // variables (e.g. when we used to randomly choose a boarding wait on each boarding,
                            // see OpenTripPlanner issue #2072 and OpenTripPlanner issue #2065).
                            // We assume that any frequency entries are uncorrelated, but this is not
                            // necessarily correct. It's a problem when the entries overlap or even are adjacent in
                            // time. See issue #122.
                            int boardTime = Integer.MAX_VALUE;
                            FREQUENCY_ENTRIES:
                            for (int freqEntryIdx = 0; freqEntryIdx < ts.headwaySeconds.length; freqEntryIdx++) {
                                int boardTimeThisEntry;

                                if (boardingAssumption == BoardingAssumption.BEST_CASE) {
                                    // Be optimistic and assume zero wait time to board every vehicle.
                                    if (inputState.bestTimes[stopIndex] + BOARD_SLACK_SECONDS > ts.endTimes[freqEntryIdx] + ts.departures[stopPositionInPattern])
                                        continue FREQUENCY_ENTRIES; // it's too late, can't board.

                                    // best case boarding time is now, or when this frequency entry starts _at this stop_,
                                    // whichever is later
                                    boardTimeThisEntry = Math.max(inputState.bestTimes[stopIndex] + BOARD_SLACK_SECONDS, ts.startTimes[freqEntryIdx] + ts.departures[stopPositionInPattern]);
                                }
                                else if (boardingAssumption == BoardingAssumption.WORST_CASE) {
                                    // Be pessimistic and assume a full headway wait before boarding any vehicle.
                                    // worst case: cannot board this entry if there is not the full headway remaining before the end of the entry, we
                                    // might miss the vehicle.
                                    if (inputState.bestTimes[stopIndex] + BOARD_SLACK_SECONDS > ts.endTimes[freqEntryIdx] + ts.departures[stopPositionInPattern] - ts.headwaySeconds[freqEntryIdx])
                                        continue FREQUENCY_ENTRIES;

                                    boardTimeThisEntry = Math.max(inputState.bestTimes[stopIndex] + BOARD_SLACK_SECONDS + ts.headwaySeconds[freqEntryIdx],
                                            ts.startTimes[freqEntryIdx] + ts.departures[stopPositionInPattern] + ts.headwaySeconds[freqEntryIdx]);
                                }
                                else {
                                    // should not throw NPE, if it does something is messed up because these should
                                    // only be null for scheduled trips on a trip pattern with some frequency trips.
                                    // we shouldn't be considering scheduled trips here.
                                    int offset = offsets.offsets.get(p)[tripScheduleIdx][freqEntryIdx];

                                    // earliest board time is start time plus travel time plus offset
                                    boardTimeThisEntry = ts.startTimes[freqEntryIdx] +
                                            ts.departures[stopPositionInPattern] +
                                            offset;

                                    while (boardTimeThisEntry < inputState.bestTimes[stopIndex] + BOARD_SLACK_SECONDS) {
                                        boardTimeThisEntry += ts.headwaySeconds[freqEntryIdx];

                                        // subtract the travel time to this stop from the board time at this stop, this gives
                                        // us the terminal departure. If the terminal departure is after the end time, the vehicle
                                        // is not running.
                                        if (boardTimeThisEntry - ts.departures[stopPositionInPattern] > ts.endTimes[freqEntryIdx]) {
                                            // can't board this frequency entry
                                            continue FREQUENCY_ENTRIES;
                                        }
                                    }
                                }

                                // if we haven't continued the outer loop yet, we could potentially board this stop
                                boardTime = Math.min(boardTime, boardTimeThisEntry);
                            }

                            if (boardTime != Integer.MAX_VALUE && boardTime < remainOnBoardTime) {
                                // make sure we board the best frequency entry at a stop
                                if (bestFreqBoardStop == stopPositionInPattern && bestFreqBoardTime < boardTime)
                                    continue;

                                // board this vehicle
                                // note: this boards the trip with the lowest headway at the given time.
                                // if there are overtaking trips all bets are off.
                                bestFreqBoardTime = boardTime;
                                bestFreqBoardStop = stopPositionInPattern;
                                bestFreqBoardStopIndex = stopIndex;
                                bestFreqTrip = ts;
                                // note that we do not break the loop in case there's another frequency entry that is better
                            }
                        }
                    }

                    // save the remain on board time. If we boarded a new trip then we know that the
                    // remain on board time must be larger than the arrival time at the stop so will
                    // not be saved; no need for an explicit check.
                    if (remainOnBoardTime != Integer.MAX_VALUE && remainOnBoardTime < max_time) {
                        if (outputState.bestNonTransferTimes[stopIndex] > remainOnBoardTime) {
                            outputState.bestNonTransferTimes[stopIndex] = remainOnBoardTime;
                            outputState.previousPatterns[stopIndex] = p;
                            outputState.previousStop[stopIndex] = bestFreqBoardStopIndex;

                            stopsTouchedThisRound.set(stopIndex);
                            stopsTouchedThisSearch.set(stopIndex);

                            if (outputState.bestTimes[stopIndex] > remainOnBoardTime) {
                                outputState.bestTimes[stopIndex] = remainOnBoardTime;
                                outputState.transferStop[stopIndex] = -1; // not reached via a transfer
                            }

                            if (outputState.bestNonTransferTimes[stopIndex] > inputState.bestNonTransferTimes[stopIndex] ||
                                    outputState.bestTimes[stopIndex] > inputState.bestTimes[stopIndex]) {
                                LOG.error("Relaxing stop increased travel time at stop {}, can't happen", stopIndex);
                            }

                            if (remainOnBoardTime < outputState.departureTime) {
                                LOG.error("Negative speed travel, path dump follows:\n{}", outputState.dump(stopIndex));
                            }
                        }
                    }
                }

                // don't mix frequencies and timetables
                // TODO should we have this condition here?
                if (bestFreqTrip != null)
                    continue PATTERNS;
            }

            // Perform a search on the scheduled routes.
            // We always perform a scheduled search, even when we're doing a frequency search as well. This is important
            // in mixed networks, because the frequency trips may allow you to reach scheduled trips more quickly. We perform
            // an initial search with only schedules which serves as a bound, but we must finish with a search that includes
            // _all_ transit service.
            TripSchedule onTrip = null;
            int onTripIdx = -1;
            int boardStopIndex = -1;
            stopPositionInPattern = -1;
            for (int stopIndex : timetable.stops) {
                stopPositionInPattern += 1;
                if (onTrip == null) {
                    // We haven't boarded yet
                    if (inputState.bestTimes[stopIndex] == UNREACHED) {
                        continue; // we've never reached this stop, we can't board.
                    }
                    // Stop has been reached before. Attempt to board here.

                    int tripIdx = -1;
                    for (TripSchedule trip : timetable.tripSchedules) {
                        tripIdx++;
                        // Do not board frequency trips or trips whose services are not active on the given day
                        if (trip.headwaySeconds != null || !servicesActive.get(trip.serviceCode))
                            continue;

                        int dep = trip.departures[stopPositionInPattern];
                        if (dep > inputState.bestTimes[stopIndex] + BOARD_SLACK_SECONDS) {
                            onTrip = trip;
                            onTripIdx = tripIdx;
                            boardStopIndex = stopIndex;
                            break; // trips are sorted, we've found the earliest usable one
                        }
                    }

                    continue; // boarded or not, we move on to the next stop in the sequence
                } else {
                    // We're on board a trip. At this particular stop on this trip, update best arrival time
                    // if we've improved on the existing one.
                    int arrivalTime = onTrip.arrivals[stopPositionInPattern];

                    if (arrivalTime > max_time)
                        // Cut off the search, don't continue searching this pattern
                        continue PATTERNS;

                    if (arrivalTime < outputState.bestNonTransferTimes[stopIndex]) {
                        outputState.bestNonTransferTimes[stopIndex] = arrivalTime;
                        outputState.previousPatterns[stopIndex] = p;
                        outputState.previousStop[stopIndex] = boardStopIndex;

                        stopsTouchedThisRound.set(stopIndex);
                        stopsTouchedThisSearch.set(stopIndex);

                        if (arrivalTime < outputState.bestTimes[stopIndex]) {
                            outputState.bestTimes[stopIndex] = arrivalTime;
                            outputState.transferStop[stopIndex] = -1; // not reached via transfer
                        }
                    }

                    // Check whether we can switch to an earlier trip, because there was a faster way to
                    // get to this stop than the trip we're currently on.
                    if (inputState.bestTimes[stopIndex] < arrivalTime) {
                        int bestTripIdx = onTripIdx;
                        // Step backward toward index 0 (inclusive!)
                        while (--bestTripIdx >= 0) {
                            TripSchedule trip = timetable.tripSchedules.get(bestTripIdx);
                            if (trip.headwaySeconds != null || !servicesActive.get(trip.serviceCode)) {
                                // This is a frequency trip or it is not running on the day of the search.
                                continue;
                            }
                            if (trip.departures[stopPositionInPattern] > inputState.bestTimes[stopIndex] + BOARD_SLACK_SECONDS) {
                                // This trip is running and departs later than we have arrived at this stop.
                                onTripIdx = bestTripIdx;
                                onTrip = trip;
                                boardStopIndex = stopIndex;
                            } else {
                                // This trip arrives at this stop too early. Trips are sorted by time, don't keep looking.
                                break;
                            }
                        }
                    }
                }
            }
        }

        doTransfers(outputState);
        // doTransfers will have marked some patterns if the search reached any stops.
        return !patternsTouchedThisRound.isEmpty();
    }

    /**
     * After a round, transfer from each stop that was updated to all nearby stops before the next round.
     * This is also where patterns are marked for exploration on future rounds;
     * all the patterns passing through stops reached this round and all patterns passing through stops transferred
     * to will be marked. We don't have separate rounds for transfers; see comments in RaptorState.
     */
    private void doTransfers(RaptorState state) {
        patternsTouchedThisRound.clear();
        for (int stop = stopsTouchedThisRound.nextSetBit(0); stop >= 0; stop = stopsTouchedThisRound.nextSetBit(stop + 1)) {
            // First, mark all patterns at this stop (the trivial "stay at the stop where you are" loop transfer).
            markPatternsForStop(stop);

            // Then follow all transfers out of this stop, marking patterns that pass through those target stops.
            int fromTime = state.bestNonTransferTimes[stop];
            TIntList transfers = data.transfersForStop.get(stop);
            // Transfers are stored as a flattened 2D array, advance two elements at a time.
            for (int i = 0; i < transfers.size(); i += 2) {
                int toStop = transfers.get(i);
                int distance = transfers.get(i + 1);
                int toTime = fromTime + (int) (distance / req.walkSpeed);
                if (toTime < max_time && toTime < state.bestTimes[toStop]) {
                    state.bestTimes[toStop] = toTime;
                    state.transferStop[toStop] = stop;
                    markPatternsForStop(toStop);
                }
            }
        }
    }

    /**
     * Propagate travel time out from transit stops to the targets.
     * Uses stopsTouchedThisSearch to determine from whence to propagate.
     *
     * This is valid both for randomized frequencies and for schedules, because the stops that have
     * been updated will be in stopsTouchedThisSearch.
     *
     * This function must be called after every search (either a minute of the scheduled search, or a frequency search
     * on top of the scheduled network). This is because propagation only occurs from stops that are marked in
     * stopsTouchedThisSearch, which is cleared before each search. See issue #137.
     */
    public void doPropagation (int[] timesAtTransitStops, int[] timesAtTargets, int departureTime) {

        // For debug and informational purposes, measure how long it takes to perform propagation.
        long beginPropagationTime = System.currentTimeMillis();

        // Record distances to each target. We need to propagate all the way to samples when doing repeated RAPTOR.
        // Consider the situation where there are two parallel transit lines on
        // 5th Street and 6th Street, and you live on A Street halfway between 5th and 6th.
        // Both lines run at 30 minute headways, but they are exactly out of phase, and for the
        // purposes of this conversation both go the same place with the same in-vehicle travel time.
        // Thus, even though the lines run every 30 minutes, you never experience a wait of more than
        // 15 minutes because you are clever when you choose which line to take. The worst case at each
        // transit stop is much worse than the worst case at samples. While unlikely, it is possible that
        // a sample would be able to reach these two stops within the walk limit, but that the two
        // intersections it is connected to cannot reach both.

        // Only loop over stops that were touched in this particular search (schedule or frequency). We are updating
        // timesAtTargets, which already contains times that were found during previous searches (either scheduled searches
        // at previous minutes, or in the case of a frequency search, the scheduled search that was run at the same minute).
        for (int s = stopsTouchedThisSearch.nextSetBit(0); s >= 0; s = stopsTouchedThisSearch.nextSetBit(s + 1)) {
            int timeAtTransitStop = timesAtTransitStops[s];
            if (timeAtTransitStop != UNREACHED) {
                int[] targets = this.targets.stopTrees.get(s);
                if (targets == null) {
                    continue;
                }
                // Targets contains pairs of (targetIndex, time).
                // The cache has time in seconds rather than distance to avoid costly floating-point divides and
                // integer casts here. FIXME this should be changed along with the transfers to use millimeters.
                for (int i = 0; i < targets.length; i += 2) {
                    int targetIndex = targets[i];
                    int propagated_time = timeAtTransitStop + targets[i + 1];

                    if (propagated_time < departureTime) {
                        LOG.error("Negative propagated time, will crash shortly.");
                    }

                    if (timesAtTargets[targetIndex] > propagated_time) {
                        timesAtTargets[targetIndex] = propagated_time;
                    }
                }
            }
        }
        totalPropagationTime += (System.currentTimeMillis() - beginPropagationTime);
    }

    /** Mark all the patterns passing through the given stop. */
    private void markPatternsForStop(int stop) {
        TIntList patterns = data.patternsForStop.get(stop);
        for (TIntIterator it = patterns.iterator(); it.hasNext();) {
            int pattern = it.next();
            patternsTouchedThisRound.set(pattern);
        }
    }
}
