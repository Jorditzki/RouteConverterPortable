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

package slash.navigation.converter.gui.actions;

import slash.navigation.converter.gui.RouteConverter;
import slash.navigation.gui.Application;
import slash.navigation.gui.actions.DialogAction;
import slash.navigation.gui.notifications.NotificationManager;
import slash.navigation.maps.mapsforge.MapsforgeMapManager;
import slash.navigation.maps.mapsforge.RemoteTheme;

import javax.swing.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;

import static java.text.MessageFormat.format;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static javax.swing.JOptionPane.ERROR_MESSAGE;
import static javax.swing.JOptionPane.showMessageDialog;
import static javax.swing.SwingUtilities.invokeLater;
import static slash.common.io.Files.asDialogString;

/**
 * {@link Action} that downloads {@link RemoteTheme}s from the {@link MapsforgeMapManager}.
 *
 * @author Christian Pesch
 */

public class DownloadThemesAction extends DialogAction {
    private static final Logger log = Logger.getLogger(DownloadThemesAction.class.getName());
    private static ExecutorService executor = newCachedThreadPool();

    private final JTable table;
    private final MapsforgeMapManager mapManager;

    public DownloadThemesAction(JDialog dialog, JTable table, MapsforgeMapManager mapManager) {
        super(dialog);
        this.table = table;
        this.mapManager = mapManager;
    }

    private Action getAction() {
        return Application.getInstance().getContext().getActionManager().get("show-downloads");
    }

    private NotificationManager getNotificationManager() {
        return Application.getInstance().getContext().getNotificationManager();
    }

    public void run() {
        final RouteConverter r = RouteConverter.getInstance();

        int[] selectedRows = table.getSelectedRows();
        if (selectedRows.length == 0)
            return;
        final List<RemoteTheme> selectedThemes = new ArrayList<>();
        List<String> selectedThemesNames = new ArrayList<>();
        for (int selectedRow : selectedRows) {
            RemoteTheme theme = mapManager.getDownloadableThemesModel().getItem(table.convertRowIndexToModel(selectedRow));
            selectedThemes.add(theme);
            selectedThemesNames.add(theme.getUrl());
        }
        getNotificationManager().showNotification(format(RouteConverter.getBundle().getString("download-started"), asDialogString(selectedThemesNames, true)), getAction());

        executor.execute(new Runnable() {
            public void run() {
                try {
                    mapManager.queueForDownload(selectedThemes);

                    mapManager.scanThemes();
                } catch (IOException e) {
                    invokeLater(new Runnable() {
                        public void run() {
                            log.warning("Could not download maps: " + e);
                            showMessageDialog(r.getFrame(), format(RouteConverter.getBundle().getString("scan-error"), e), r.getFrame().getTitle(), ERROR_MESSAGE);
                        }
                    });
                }
            }
        });
    }
}
