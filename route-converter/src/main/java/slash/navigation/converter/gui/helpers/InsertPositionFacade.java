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
package slash.navigation.converter.gui.helpers;

import slash.navigation.base.BaseNavigationPosition;
import slash.navigation.common.LongitudeAndLatitude;
import slash.navigation.common.NavigationPosition;
import slash.navigation.converter.gui.RouteConverter;
import slash.navigation.converter.gui.models.PositionsModel;
import slash.navigation.gui.Application;
import slash.navigation.routing.DownloadFuture;
import slash.navigation.routing.RoutingResult;
import slash.navigation.routing.RoutingService;
import slash.navigation.routing.TravelMode;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

import static java.lang.String.format;
import static javax.swing.JOptionPane.ERROR_MESSAGE;
import static javax.swing.JOptionPane.showMessageDialog;
import static javax.swing.SwingUtilities.invokeAndWait;
import static javax.swing.SwingUtilities.invokeLater;
import static slash.common.helpers.ExceptionHelper.getLocalizedMessage;
import static slash.common.helpers.ExceptionHelper.printStackTrace;
import static slash.common.helpers.ThreadHelper.createSingleThreadExecutor;
import static slash.common.io.Transfer.toArray;
import static slash.navigation.gui.helpers.WindowHelper.getFrame;
import static slash.navigation.routing.RoutingResult.Validity.*;

/**
 * Helps to insert positions.
 *
 * @author Christian Pesch
 */

public class InsertPositionFacade {
    private static final Logger log = Logger.getLogger(InsertPositionFacade.class.getName());

    public void insertAllWaypoints() {
        RouteConverter r = RouteConverter.getInstance();
        int[] selectedRows = r.getConvertPanel().getPositionsView().getSelectedRows();
        r.clearSelection();

        RoutingService service = r.getRoutingServiceFacade().getRoutingService();
        if (service instanceof GoogleDirections) {
            ((GoogleDirections) service).insertAllWaypoints(selectedRows);
        } else
            insertWithRoutingService(service, selectedRows);
    }

    public void insertOnlyTurnpoints() {
        RouteConverter r = RouteConverter.getInstance();
        int[] selectedRows = r.getConvertPanel().getPositionsView().getSelectedRows();
        r.clearSelection();

        RoutingService service = r.getRoutingServiceFacade().getRoutingService();
        if (service instanceof GoogleDirections) {
            ((GoogleDirections) service).insertOnlyTurnpoints(selectedRows);
        } else
            throw new UnsupportedOperationException();
    }

    private final ExecutorService executor = createSingleThreadExecutor("InsertPositions");

    private void insertWithRoutingService(final RoutingService routingService, final int[] selectedRows) {
        executor.execute(() -> {
            try {
                doInsertWithRoutingService(routingService, selectedRows);
            } catch (Exception e) {
                log.severe(format("Cannot insert positions: %s, %s", e, printStackTrace(e)));
                showMessageDialog(getFrame(), format(Application.getInstance().getContext().getBundle().getString("cannot-insert-positions"), getLocalizedMessage(e)),
                        getFrame().getTitle(), ERROR_MESSAGE);
            }
        });
    }

    private void doInsertWithRoutingService(RoutingService routingService, int[] selectedRows) throws InvocationTargetException, InterruptedException {
        RouteConverter r = RouteConverter.getInstance();
        PositionsModel positionsModel = r.getConvertPanel().getPositionsModel();

        List<NavigationPosition> selectedPositions = new ArrayList<>();
        for (int selectedRow : selectedRows)
            selectedPositions.add(positionsModel.getPosition(selectedRow));

        DownloadFuture future = null;
        if (routingService.isDownload()) {
            List<LongitudeAndLatitude> longitudeAndLatitudes = new ArrayList<>();
            for (NavigationPosition position : selectedPositions) {
                longitudeAndLatitudes.add(new LongitudeAndLatitude(position.getLongitude(), position.getLatitude()));
            }
            future = routingService.downloadRoutingDataFor(r.getMapView().getMapIdentifier(), longitudeAndLatitudes);
        }

        TravelMode travelMode = r.getRoutingServiceFacade().getRoutingPreferencesModel().getTravelMode();
        List<Integer> positions = insertPositions(routingService, future, travelMode, selectedPositions);
        if (positions.size() > 0)
            invokeLater(() -> r.getPositionAugmenter().addData(toArray(positions), false, true, true, false, false));
    }

    private List<Integer> insertPositions(RoutingService routingService, DownloadFuture future, TravelMode travelMode, List<NavigationPosition> selectedPositions) throws InterruptedException, InvocationTargetException {
        PositionsModel positionsModel = RouteConverter.getInstance().getConvertPanel().getPositionsModel();

        List<Integer> insertedPositions = new ArrayList<>();
        for (int i = 0; i < selectedPositions.size(); i++) {
            // skip the very last position without successor
            if (i == positionsModel.getRowCount() - 1 || i == selectedPositions.size() - 1)
                continue;

            RoutingResult result = routingService.getRouteBetween(selectedPositions.get(i), selectedPositions.get(i + 1), travelMode);
            if (result.getValidity().equals(Valid)) {
                final List<BaseNavigationPosition> positions = new ArrayList<>();
                for (NavigationPosition position : result.getPositions()) {
                    positions.add(positionsModel.getRoute().createPosition(position.getLongitude(), position.getLatitude(), position.getElevation(), null, null, null));
                }
                final int insertRow = positionsModel.getIndex(selectedPositions.get(i)) + 1;

                // wait for adding to positions model to be completed before inserting the next positions
                invokeAndWait(() -> positionsModel.add(insertRow, positions));

                // collect rows to augment them in a batch
                for (int j = 0; j < positions.size(); j++)
                    insertedPositions.add(insertRow + j);
            }
        }
        return insertedPositions;
    }
}
