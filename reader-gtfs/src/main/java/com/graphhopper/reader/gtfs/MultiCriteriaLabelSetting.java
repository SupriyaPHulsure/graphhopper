/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.reader.gtfs;

import com.carrotsearch.hppc.IntObjectHashMap;
import com.carrotsearch.hppc.IntObjectMap;
import com.graphhopper.routing.profiles.IntEncodedValue;
import com.graphhopper.util.EdgeIterator;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Implements a Multi-Criteria Label Setting (MLS) path finding algorithm
 * with the criteria earliest arrival time and number of transfers.
 * <p>
 *
 * @author Michael Zilske
 * @author Peter Karich
 * @author Wesam Herbawi
 */
public class MultiCriteriaLabelSetting {

    public interface SPTVisitor {
        void visit(Label label);
    }

    private final Comparator<Label> queueComparator;
    private final List<Label> targetLabels;
    private long startTime;
    private int blockedRouteTypes;
    private final PtFlagEncoder flagEncoder;
    private final IntObjectMap<List<Label>> fromMap;
    private final PriorityQueue<Label> fromHeap;
    private final int maxVisitedNodes;
    private final boolean reverse;
    private final double maxBikeDistancePerLeg;
    private final boolean ptOnly;
    private final boolean mindTransfers;
    private final boolean profileQuery;
    private int visitedNodes;
    private final GraphExplorer explorer;
    private double betaTransfers;
    private double betaBikeTime = 1.0;

    public MultiCriteriaLabelSetting(GraphExplorer explorer, PtFlagEncoder flagEncoder, boolean reverse, double maxBikeDistancePerLeg, boolean ptOnly, boolean mindTransfers, boolean profileQuery, int maxVisitedNodes, List<Label> solutions) {
        this.flagEncoder = flagEncoder;
        this.maxVisitedNodes = maxVisitedNodes;
        this.explorer = explorer;
        this.reverse = reverse;
        this.maxBikeDistancePerLeg = maxBikeDistancePerLeg;
        this.ptOnly = ptOnly;
        this.mindTransfers = mindTransfers;
        this.profileQuery = profileQuery;
        this.targetLabels = solutions;

        queueComparator = Comparator
                .comparingLong(this::weight)
                .thenComparingLong(l -> l.nTransfers)
                .thenComparingLong(l -> l.bikeTime)
                .thenComparingLong(l -> departureTimeCriterion(l) != null ? departureTimeCriterion(l) : 0)
                .thenComparingLong(l -> l.impossible ? 1 : 0);
        fromHeap = new PriorityQueue<>(queueComparator);
        fromMap = new IntObjectHashMap<>();
    }

    Stream<Label> calcLabels(int from, int to, Instant startTime, int blockedRouteTypes) {
        this.startTime = startTime.toEpochMilli();
        this.blockedRouteTypes = blockedRouteTypes;
        return StreamSupport.stream(new MultiCriteriaLabelSettingSpliterator(from, to), false)
                .limit(maxVisitedNodes)
                .peek(label -> visitedNodes++);
    }

    public void calcLabels(int from, int to, Instant startTime, int blockedRouteTypes, SPTVisitor visitor, Predicate<Label> predicate) {
        this.startTime = startTime.toEpochMilli();
        this.blockedRouteTypes = blockedRouteTypes;
        Iterator<Label> iterator = StreamSupport.stream(new MultiCriteriaLabelSettingSpliterator(from, to), false).iterator();
        Label l;
        while (iterator.hasNext() && predicate.test(l = iterator.next())) {
            visitor.visit(l);
        }
    }


    public void calcLabelsAndNeighbors(int from, int to, Instant startTime, int blockedRouteTypes, SPTVisitor visitor, Predicate<Label> predicate) {
        this.startTime = startTime.toEpochMilli();
        this.blockedRouteTypes = blockedRouteTypes;
        Iterator<Label> iterator = StreamSupport.stream(new MultiCriteriaLabelSettingSpliterator(from, to), false).iterator();
        Label l;
        while (iterator.hasNext() && predicate.test(l = iterator.next())) {
            visitor.visit(l);
        }
        for (Label label : fromHeap) {
            visitor.visit(label);
        }
    }

    // experimental
    void setBetaTransfers(double betaTransfers) {
        this.betaTransfers = betaTransfers;
    }

    // experimental
    void setBetaBikeTime(double betaBikeTime) {
        this.betaBikeTime = betaBikeTime;
    }

    private class MultiCriteriaLabelSettingSpliterator extends Spliterators.AbstractSpliterator<Label> {

        private final int from;
        private final int to;

        MultiCriteriaLabelSettingSpliterator(int from, int to) {
            super(0, 0);
            this.from = from;
            this.to = to;
            Label label = new Label(startTime, EdgeIterator.NO_EDGE, from, 0, 0, 0.0, null, 0, 0, false, null, 0);
            ArrayList<Label> labels = new ArrayList<>(1);
            labels.add(label);
            fromMap.put(from, labels);
            fromHeap.add(label);
        }

        @Override
        public boolean tryAdvance(Consumer<? super Label> action) {
            if (fromHeap.isEmpty()) {
                return false;
            } else {
                Label label = fromHeap.poll();
                action.accept(label);
                final IntEncodedValue validityEnc = flagEncoder.getValidityIdEnc();
                explorer.exploreEdgesAround(label).forEach(edge -> {
                    GtfsStorage.EdgeType edgeType = edge.get(flagEncoder.getTypeEnc());
                    if (edgeType == GtfsStorage.EdgeType.ENTER_PT && reverse && ptOnly) return;
                    if (edgeType == GtfsStorage.EdgeType.EXIT_PT && !reverse && ptOnly) return;
                    if ((edgeType == GtfsStorage.EdgeType.ENTER_PT || edgeType == GtfsStorage.EdgeType.EXIT_PT) && (blockedRouteTypes & (1 << edge.get(validityEnc))) != 0)
                        return;
                    long travelTime;
                    long nextTime;
                    if (reverse) {
                        nextTime = label.currentTime - explorer.calcTravelTimeMillis(edge, label.currentTime, false);
                        travelTime = label.currentTime - explorer.calcTravelTimeMillis(edge, label.currentTime, true);
                    } else {
                        nextTime = label.currentTime + explorer.calcTravelTimeMillis(edge, label.currentTime, false);
                        travelTime = label.currentTime + explorer.calcTravelTimeMillis(edge, label.currentTime, true);
                    }
                    int nTransfers = label.nTransfers + explorer.calcNTransfers(edge);
                    Long firstPtDepartureTime = label.departureTime;
                    if (!reverse && (edgeType == GtfsStorage.EdgeType.ENTER_TIME_EXPANDED_NETWORK || edgeType == GtfsStorage.EdgeType.WAIT)) {
                        if (label.nTransfers == 0) {
                            firstPtDepartureTime = nextTime - label.bikeTime;
                        }
                    } else if (reverse && (edgeType == GtfsStorage.EdgeType.LEAVE_TIME_EXPANDED_NETWORK || edgeType == GtfsStorage.EdgeType.WAIT_ARRIVAL)) {
                        if (label.nTransfers == 0) {
                            firstPtDepartureTime = nextTime - label.bikeTime;
                        }
                    }
                    double bikeDistanceOnCurrentLeg = (!reverse && edgeType == GtfsStorage.EdgeType.BOARD || reverse && edgeType == GtfsStorage.EdgeType.ALIGHT) ? 0 : (label.bikeDistanceOnCurrentLeg + edge.getDistance());
                    boolean isTryingToReEnterPtAfterBiking = (!reverse && edgeType == GtfsStorage.EdgeType.ENTER_PT || reverse && edgeType == GtfsStorage.EdgeType.EXIT_PT) && label.nTransfers > 0;
                    long bikeTime = label.bikeTime + (edgeType == GtfsStorage.EdgeType.HIGHWAY || edgeType == GtfsStorage.EdgeType.ENTER_PT || edgeType == GtfsStorage.EdgeType.EXIT_PT ? ((reverse ? -1 : 1) * (nextTime - label.currentTime)) : 0);
                    int nBikeDistanceConstraintViolations = Math.min(1, label.nBikeDistanceConstraintViolations + (
                            isTryingToReEnterPtAfterBiking ? 1 : (label.bikeDistanceOnCurrentLeg <= maxBikeDistancePerLeg && bikeDistanceOnCurrentLeg > maxBikeDistancePerLeg ? 1 : 0)));
                    List<Label> sptEntries = fromMap.get(edge.getAdjNode());
                    if (sptEntries == null) {
                        sptEntries = new ArrayList<>(1);
                        fromMap.put(edge.getAdjNode(), sptEntries);
                    }
                    boolean impossible = label.impossible
                            || explorer.isBlocked(edge)
                            || (!reverse) && edgeType == GtfsStorage.EdgeType.BOARD && label.residualDelay > 0
                            || reverse && edgeType == GtfsStorage.EdgeType.ALIGHT && label.residualDelay < explorer.getDelayFromAlightEdge(edge, label.currentTime);
                    long residualDelay;
                    long travelTimeDelay = 0;
                    if (!reverse) {
                        if (edgeType == GtfsStorage.EdgeType.WAIT || edgeType == GtfsStorage.EdgeType.TRANSFER) {
                            residualDelay = Math.max(0, label.residualDelay - explorer.calcTravelTimeMillis(edge, label.currentTime, false));
                        } else if (edgeType == GtfsStorage.EdgeType.ALIGHT) {
                            residualDelay = label.residualDelay + explorer.getDelayFromAlightEdge(edge, label.currentTime);
                        } else if (edgeType == GtfsStorage.EdgeType.BOARD) {
                            residualDelay = -explorer.getDelayFromBoardEdge(edge, label.currentTime);
                        } else {
                            residualDelay = label.residualDelay;
                            travelTimeDelay = label.travelTime;
                        }
                    } else {
                        if (edgeType == GtfsStorage.EdgeType.WAIT || edgeType == GtfsStorage.EdgeType.TRANSFER) {
                            residualDelay = label.residualDelay + explorer.calcTravelTimeMillis(edge, label.currentTime, false);
                        } else {
                            residualDelay = 0;
                        }
                    }
                    if (!reverse && edgeType == GtfsStorage.EdgeType.LEAVE_TIME_EXPANDED_NETWORK && residualDelay > 0) {
                        Label newImpossibleLabelForDelayedTrip = new Label(nextTime, edge.getEdge(), edge.getAdjNode(), nTransfers, nBikeDistanceConstraintViolations, bikeDistanceOnCurrentLeg, firstPtDepartureTime, bikeTime, residualDelay, true, label, travelTime);
                        insertIfNotDominated(sptEntries, newImpossibleLabelForDelayedTrip);
                        nextTime += residualDelay;
                        travelTime += travelTimeDelay;
                        residualDelay = 0;
                        Label newLabel = new Label(nextTime, edge.getEdge(), edge.getAdjNode(), nTransfers, nBikeDistanceConstraintViolations, bikeDistanceOnCurrentLeg, firstPtDepartureTime, bikeTime, residualDelay, impossible, label, travelTime);
                        insertIfNotDominated(sptEntries, newLabel);
                    } else {
                        Label newLabel = new Label(nextTime, edge.getEdge(), edge.getAdjNode(), nTransfers, nBikeDistanceConstraintViolations, bikeDistanceOnCurrentLeg, firstPtDepartureTime, bikeTime, residualDelay, impossible, label, travelTime);
                        insertIfNotDominated(sptEntries, newLabel);
                    }
                });
                return true;
            }
        }

        private void insertIfNotDominated(Collection<Label> sptEntries, Label label) {
            if (isNotDominatedByAnyOf(label, sptEntries)) {
                if (isNotDominatedByAnyOf(label, targetLabels)) {
                    removeDominated(label, sptEntries);
                    sptEntries.add(label);
                    fromHeap.add(label);
                }
            }
        }
    }

    boolean isNotDominatedByAnyOf(Label me, Collection<Label> sptEntries) {
        if (me.nBikeDistanceConstraintViolations > 0) {
            return false;
        }
        for (Label they : sptEntries) {
            if (dominates(they, me)) {
                return false;
            }
        }
        return true;
    }

    void removeDominated(Label me, Collection<Label> sptEntries) {
        for (Iterator<Label> iterator = sptEntries.iterator(); iterator.hasNext(); ) {
            Label sptEntry = iterator.next();
            if (dominates(me, sptEntry)) {
                fromHeap.remove(sptEntry);
                iterator.remove();
            }
        }
    }

    private boolean dominates(Label me, Label they) {
        if(travelTimeWeight(me) > travelTimeWeight(they))
            return false;

        if (weight(me) > weight(they))
            return false;

        if (profileQuery) {
            if (me.departureTime != null && they.departureTime != null) {
                if (departureTimeCriterion(me) > departureTimeCriterion(they))
                    return false;
            } else {
                if (travelTimeCriterion(me) > travelTimeCriterion(they))
                    return false;
            }
        }

        if (mindTransfers && me.nTransfers > they.nTransfers)
            return false;
        if (me.nBikeDistanceConstraintViolations > they.nBikeDistanceConstraintViolations)
            return false;
        if (me.impossible && !they.impossible)
            return false;

        if(travelTimeWeight(me) < travelTimeWeight(they))
            return true;

        if (weight(me) < weight(they))
            return true;

        if (profileQuery) {
            if (me.departureTime != null && they.departureTime != null) {
                if (departureTimeCriterion(me) < departureTimeCriterion(they))
                    return true;
            } else {
                if (travelTimeCriterion(me) < travelTimeCriterion(they))
                    return true;
            }
        }
        if (mindTransfers && me.nTransfers < they.nTransfers)
            return true;
        if (me.nBikeDistanceConstraintViolations < they.nBikeDistanceConstraintViolations)
            return true;

        return queueComparator.compare(me, they) <= 0;
    }

    private Long departureTimeCriterion(Label label) {
        return label.departureTime == null ? null : reverse ? label.departureTime : -label.departureTime;
    }

    long weight(Label label) {
        return (reverse ? -1 : 1) * (label.currentTime - startTime) + (long) (label.nTransfers * betaTransfers) + (long) (label.bikeTime * (betaBikeTime - 1.0));
    }

    long travelTimeWeight(Label label) {
        return (reverse ? -1 : 1) * (label.travelTime) + (long) (label.nTransfers * betaTransfers) + (long) (label.bikeTime * (betaBikeTime - 1.0));
    }

    private long travelTimeCriterion(Label label) {
        if (label.departureTime == null) {
            return label.bikeTime;
        } else {
            return (reverse ? -1 : 1) * (label.currentTime - label.departureTime);
        }
    }

    int getVisitedNodes() {
        return visitedNodes;
    }

}
