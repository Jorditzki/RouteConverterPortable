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

import slash.navigation.converter.gui.RouteConverter;
import slash.navigation.gui.Application;
import slash.navigation.gui.notifications.NotificationManager;
import slash.navigation.routing.RoutingService;

import javax.swing.*;
import java.text.MessageFormat;
import java.util.ResourceBundle;

/**
 * Shows notifications via the {@link NotificationManager} upon {@link RoutingServiceFacadeListener} events on {@link RoutingService}s.
 *
 * @author Christian Pesch
 */

public class RoutingServiceFacadeNotifier implements RoutingServiceFacadeListener {
    private Action getAction() {
        return Application.getInstance().getContext().getActionManager().get("show-options");
    }

    private ResourceBundle getBundle() {
        return Application.getInstance().getContext().getBundle();
    }

    private void showNotification(String message) {
        Application.getInstance().getContext().getNotificationManager().showNotification(message, getAction());
    }

    public void downloading() {
        String message = getBundle().getString("downloading-routing-data");
        showNotification(message);
    }

    public void processing(int second) {
        String message = MessageFormat.format(getBundle().getString("processing-routing-data"), second);
        showNotification(message);
    }

    public void routing(int second) {
        String message = MessageFormat.format(getBundle().getString("running-routing"), second);
        showNotification(message);
    }

    public void preferencesChanged() {
        RouteConverter.getInstance().getMapView().routingPreferencesChanged();
    }
}
