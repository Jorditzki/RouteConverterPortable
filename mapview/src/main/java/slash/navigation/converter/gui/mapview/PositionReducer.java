/*
    This file is part of RouteConverter.

    RouteConverter is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    RouteConverter is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with RouteConverter; if not, write to the Free Software
    Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA

    Copyright (C) 2007 Christian Pesch. All Rights Reserved.
*/

package slash.navigation.converter.gui.mapview;

import slash.navigation.base.NavigationPosition;
import slash.navigation.base.RouteCharacteristics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.lang.String.format;
import static java.lang.System.currentTimeMillis;
import static slash.navigation.base.RouteCharacteristics.Route;
import static slash.navigation.base.RouteCharacteristics.Waypoints;
import static slash.navigation.util.Positions.contains;
import static slash.navigation.util.Positions.getSignificantPositions;
import static slash.navigation.util.Positions.northEast;
import static slash.navigation.util.Positions.southWest;

/**
 * Helps to reduce the amount of positions for rending routes, tracks, waypoint lists.
 *
 * @author Christian Pesch
 */

class PositionReducer {
    private static final Preferences preferences = Preferences.userNodeForPackage(MapView.class);
    private static final Logger log = Logger.getLogger(MapView.class.getName());

    private static final double[] THRESHOLD_PER_ZOOM = {
            120000,
            70000,
            40000,
            20000,
            10000,    // level 4
            2700,
            2100,
            1500,
            800,      // level 8
            500,
            225,
            125,
            75,
            50,
            20,
            10,
            4,
            1         // level 17
    };
    private static final int MAXIMUM_ZOOM_FOR_SIGNIFICANCE_CALCULATION = THRESHOLD_PER_ZOOM.length;

    private final Callback callback;
    private final Map<Integer, List<NavigationPosition>> reducedPositions = new HashMap<Integer, List<NavigationPosition>>(THRESHOLD_PER_ZOOM.length);
    private NavigationPosition visibleNorthEast, visibleSouthWest;

    PositionReducer(Callback callback) {
        this.callback = callback;
    }

    public List<NavigationPosition> reducePositions(List<NavigationPosition> positions, RouteCharacteristics characteristics) {
        if (positions.size() < 3)
            return positions;

        int zoom = callback.getZoom();
        List<NavigationPosition> result = reducedPositions.get(zoom);
        if (result == null) {
            result = reducePositions(positions, zoom, characteristics);
            reducedPositions.put(zoom, result);
        }
        return result;
    }

    public List<NavigationPosition> reduceSelectedPositions(List<NavigationPosition> positions, int[] indices) {
        List<NavigationPosition> result = filterPositionsWithoutCoordinates(positions);

        // reduce selected result if they're not selected
        result = filterSelectedPositions(result, indices);

        // reduce the number of selected result by a visibility heuristic
        int maximumSelectionCount = preferences.getInt("maximumSelectionCount", 5 * 10);
        if (result.size() > maximumSelectionCount) {
            double visibleSelectedPositionAreaFactor = preferences.getDouble("visibleSelectionAreaFactor", 1.25);
            result = filterVisiblePositions(result, visibleSelectedPositionAreaFactor, true);
        }

        // reduce the number of visible result by a JS-stability heuristic
        if (result.size() > maximumSelectionCount)
            result = filterEveryNthPosition(result, maximumSelectionCount);

        return result;
    }

    public boolean hasFilteredVisibleArea() {
        return visibleNorthEast != null && visibleSouthWest != null;
    }

    public boolean isWithinVisibleArea(NavigationPosition northEastCorner,
                                       NavigationPosition southWestCorner) {
        return !hasFilteredVisibleArea() ||
                contains(visibleNorthEast, visibleSouthWest, northEastCorner) &&
                        contains(visibleNorthEast, visibleSouthWest, southWestCorner);
    }

    public void clear() {
        reducedPositions.clear();
        visibleNorthEast = null;
        visibleSouthWest = null;
    }

    interface Callback {
        int getZoom();
        NavigationPosition getNorthEastBounds();
        NavigationPosition getSouthWestBounds();
    }


    private int getMaximumPositionCount(RouteCharacteristics characteristics) {
        switch (characteristics) {
            case Route:
                return preferences.getInt("maximumRoutePositionCount", 30 * 8);
            case Track:
                return preferences.getInt("maximumTrackPositionCount", 50 * 35);
            case Waypoints:
                return preferences.getInt("maximumWaypointPositionCount", 50 * 10);
            default:
                throw new IllegalArgumentException("RouteCharacteristics " + characteristics + " is not supported");
        }
    }

    private List<NavigationPosition> reducePositions(List<NavigationPosition> positions, int zoom, RouteCharacteristics characteristics) {
        List<NavigationPosition> result = filterPositionsWithoutCoordinates(positions);
        int maximumPositionCount = getMaximumPositionCount(characteristics);

        // reduce the number of result to those that are visible for tracks and waypoint lists
        if (result.size() > maximumPositionCount && !characteristics.equals(Route)) {
            double visiblePositionAreaFactor = preferences.getDouble("visiblePositionAreaFactor", 3.0);
            double factor = max(visiblePositionAreaFactor * (zoom - MAXIMUM_ZOOM_FOR_SIGNIFICANCE_CALCULATION), 1) * visiblePositionAreaFactor;
            result = filterVisiblePositions(result, factor, false);
            visibleNorthEast = northEast(result);
            visibleSouthWest = southWest(result);
        } else {
            visibleNorthEast = null;
            visibleSouthWest = null;
        }

        // reduce the number of result by selecting every Nth to limit significance computation time
        int maximumSignificantPositionCount = preferences.getInt("maximumSignificantPositionCount", 50000);
        if (result.size() > maximumSignificantPositionCount)
            result = filterEveryNthPosition(result, maximumSignificantPositionCount);

        // determine significant result for routes and tracks for this zoom level
        if (!characteristics.equals(Waypoints))
            result = filterSignificantPositions(result, zoom);

        // reduce the number of result to ensure browser stability
        if (result.size() > maximumPositionCount)
            result = filterEveryNthPosition(result, maximumPositionCount);

        return result;
    }

    private List<NavigationPosition> filterPositionsWithoutCoordinates(List<NavigationPosition> positions) {
        long start = currentTimeMillis();

        List<NavigationPosition> result = new ArrayList<NavigationPosition>();
        for (NavigationPosition position : positions) {
            if (position.hasCoordinates())
                result.add(position);
        }

        long end = currentTimeMillis();
        if (positions.size() != result.size())
            log.info(format("Filtered positions without coordinates to reduce %d positions to %d in %d milliseconds",
                    positions.size(), result.size(), (end - start)));
        return result;
    }

    private List<NavigationPosition> filterSignificantPositions(List<NavigationPosition> positions, int zoom) {
        long start = currentTimeMillis();

        List<NavigationPosition> result = new ArrayList<NavigationPosition>();
        if (zoom < MAXIMUM_ZOOM_FOR_SIGNIFICANCE_CALCULATION) {
            double threshold = THRESHOLD_PER_ZOOM[zoom];
            int[] significantPositions = getSignificantPositions(positions, threshold);
            for (int significantPosition : significantPositions) {
                result.add(positions.get(significantPosition));
            }
            log.info(format("zoom %d smaller than %d: for threshold %f use %d significant positions",
                    zoom, MAXIMUM_ZOOM_FOR_SIGNIFICANCE_CALCULATION, threshold, significantPositions.length));
        } else {
            // on all zoom about MAXIMUM_ZOOM_FOR_SIGNIFICANCE_CALCULATION
            // use all positions since the calculation is too expensive
            result.addAll(positions);
            log.info("zoom " + zoom + " large: use all " + positions.size() + " positions");
        }

        long end = currentTimeMillis();
        if (positions.size() != result.size())
            log.info(format("Filtered significant positions to reduce %d positions to %d in %d milliseconds",
                positions.size(), result.size(), (end - start)));
        return result;
    }

    List<NavigationPosition> filterVisiblePositions(List<NavigationPosition> positions,
                                                    double threshold, boolean includeFirstAndLastPosition) {
        long start = currentTimeMillis();

        NavigationPosition northEast = callback.getNorthEastBounds();
        NavigationPosition southWest = callback.getSouthWestBounds();
        if (northEast == null || southWest == null)
            return positions;

        double width = abs(northEast.getLongitude() - southWest.getLongitude()) * threshold;
        double height = abs(southWest.getLatitude() - northEast.getLatitude()) * threshold;
        northEast.setLongitude(northEast.getLongitude() + width);
        northEast.setLatitude(northEast.getLatitude() + height);
        southWest.setLongitude(southWest.getLongitude() - width);
        southWest.setLatitude(southWest.getLatitude() - height);

        List<NavigationPosition> result = new ArrayList<NavigationPosition>();

        if (includeFirstAndLastPosition)
            result.add(positions.get(0));

        int firstIndex = includeFirstAndLastPosition ? 1 : 0;
        int lastIndex = includeFirstAndLastPosition ? positions.size() - 1 : positions.size();

        NavigationPosition previousPosition = positions.get(firstIndex);
        boolean previousPositionVisible = contains(northEast, southWest, previousPosition);

        for (int i = firstIndex; i < lastIndex; i += 1) {
            NavigationPosition position = positions.get(i);
            boolean visible = contains(northEast, southWest, position);
            if (visible) {
                // if the previous position was not visible but the current position is visible:
                // add the previous position to render transition from non-visible to visible area
                if (!previousPositionVisible && previousPosition != null)
                    result.add(previousPosition);
                result.add(position);
            } else {
                // if the previous position was visible but the current position is not visible:
                // add the current position to render transition from visible to non-visible area
                if (previousPositionVisible)
                    result.add(position);
            }

            previousPositionVisible = visible;
            previousPosition = position;
        }

        if (includeFirstAndLastPosition)
            result.add(positions.get(positions.size() - 1));

        long end = currentTimeMillis();
        if (positions.size() != result.size())
            log.info(format("Filtered visible positions with a threshold of %f to reduce %d positions to %d in %d milliseconds",
                threshold, positions.size(), result.size(), (end - start)));
        return result;
    }

    List<NavigationPosition> filterEveryNthPosition(List<NavigationPosition> positions, int maximumPositionCount) {
        long start = currentTimeMillis();

        List<NavigationPosition> result = new ArrayList<NavigationPosition>();
        result.add(positions.get(0));

        double increment = (positions.size() - 1) / (double) (maximumPositionCount - 1);
        for (double i = (increment + 1.0); i < positions.size() - 1; i += increment)
            result.add(positions.get((int) i));

        result.add(positions.get(positions.size() - 1));

        long end = currentTimeMillis();
        if (positions.size() != result.size())
            log.info(format("Filtered every %fth position to reduce %d positions to %d in %d milliseconds",
                    increment, positions.size(), result.size(), (end - start)));
        return result;
    }

    private List<NavigationPosition> filterSelectedPositions(List<NavigationPosition> positions, int[] selectedIndices) {
        long start = currentTimeMillis();

        List<NavigationPosition> result = new ArrayList<NavigationPosition>();
        for (int selectedIndex : selectedIndices) {
            if (selectedIndex >= positions.size())
                continue;
            result.add(positions.get(selectedIndex));
        }

        long end = currentTimeMillis();
        if (positions.size() != result.size())
            log.info(format("Filtered selected positions to reduce %d positions to %d in %d milliseconds",
                    selectedIndices.length, result.size(), (end - start)));
        return result;
    }
}