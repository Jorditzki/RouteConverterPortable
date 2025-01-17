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

package slash.navigation.base;

import slash.common.io.NotClosingUnderlyingInputStream;
import slash.common.type.CompactCalendar;
import slash.navigation.babel.BabelFormat;
import slash.navigation.bcr.BcrFormat;
import slash.navigation.copilot.CoPilotFormat;
import slash.navigation.gpx.Gpx11Format;
import slash.navigation.gpx.GpxFormat;
import slash.navigation.itn.TomTomRouteFormat;
import slash.navigation.kml.Kml22Format;
import slash.navigation.nmn.NmnFormat;
import slash.navigation.photo.PhotoFormat;
import slash.navigation.rest.Get;
import slash.navigation.tcx.TcxFormat;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static java.io.File.separatorChar;
import static java.lang.Math.min;
import static java.lang.String.format;
import static slash.common.io.Files.getExtension;
import static slash.common.io.Transfer.ceiling;
import static slash.common.type.CompactCalendar.UTC;
import static slash.common.type.CompactCalendar.fromCalendar;
import static slash.navigation.base.NavigationFormatConverter.asFormat;
import static slash.navigation.base.NavigationFormatConverter.convertRoute;
import static slash.navigation.base.RouteComments.*;
import static slash.navigation.url.GoogleMapsUrlFormat.isGoogleMapsProfileUrl;

/**
 * Parses byte streams with navigation information via {@link NavigationFormat} classes.
 *
 * @author Christian Pesch
 */

public class NavigationFormatParser {
    private static final Logger log = Logger.getLogger(NavigationFormatParser.class.getName());
    public static final int TOTAL_BUFFER_SIZE = 1024 * 1024;
    private static final int CHUNK_BUFFER_SIZE = 8 * 1024;
    private final NavigationFormatRegistry navigationFormatRegistry;
    private final List<NavigationFormatParserListener> listeners = new CopyOnWriteArrayList<>();

    public NavigationFormatParser(NavigationFormatRegistry navigationFormatRegistry) {
        this.navigationFormatRegistry = navigationFormatRegistry;
    }

    public NavigationFormatRegistry getNavigationFormatRegistry() {
        return navigationFormatRegistry;
    }

    public void addNavigationFileParserListener(NavigationFormatParserListener listener) {
        listeners.add(listener);
    }

    public void removeNavigationFileParserListener(NavigationFormatParserListener listener) {
        listeners.remove(listener);
    }

    private void notifyReading(NavigationFormat<BaseRoute> format) {
        for (NavigationFormatParserListener listener : listeners) {
            listener.reading(format);
        }
    }

    private List<Integer> getPositionCounts(List<BaseRoute> routes) {
        List<Integer> positionCounts = new ArrayList<>();
        for (BaseRoute route : routes)
            // guard against strange effects in tests only
            if (route != null)
                positionCounts.add(route.getPositionCount());
        return positionCounts;
    }

    @SuppressWarnings("unchecked")
    private void internalRead(InputStream buffer, List<NavigationFormat> formats, ParserContext context) throws IOException {
        int routeCountBefore = context.getRoutes().size();
        NavigationFormat firstSuccessfulFormat = null;

        try {
            for (NavigationFormat<BaseRoute> format : formats) {
                notifyReading(format);

                log.fine(format("Trying to read with %s", format));
                try {
                    format.read(buffer, context);

                    // if no route has been read, take the first that didn't throw an exception
                    if (firstSuccessfulFormat == null)
                        firstSuccessfulFormat = format;
                } catch (Exception e) {
                    log.severe(format("Error reading with %s: %s", format, e));
                }

                if (context.getRoutes().size() > routeCountBefore) {
                    context.addFormat(format);
                    break;
                }

                try {
                    buffer.reset();
                } catch (IOException e) {
                    log.severe("Cannot reset() stream to mark(): " + e.getLocalizedMessage());
                    break;
                }
            }
        } finally {
            buffer.close();
        }

        if (context.getRoutes().size() == 0 && context.getFormats().size() == 0 && firstSuccessfulFormat != null)
            context.addFormat(firstSuccessfulFormat);
    }

    public ParserResult read(File source, List<NavigationFormat> formats) throws IOException {
        log.info("Reading '" + source.getAbsolutePath() + "' by " + formats.size() + " formats");
        try (InputStream inputStream = new FileInputStream(source)) {
            return read(inputStream, (int) source.length(), extractStartDate(source), source, formats);
        }
    }

    public ParserResult read(File source) throws IOException {
        return read(source, getNavigationFormatRegistry().getReadFormatsPreferredByExtension(getExtension(source)));
    }

    private NavigationFormat determineFormat(List<BaseRoute> routes, NavigationFormat preferredFormat) {
        NavigationFormat result = preferredFormat;
        for (BaseRoute route : routes) {
            // more than one route: the same result
            if (result.equals(route.getFormat()))
                continue;

            // result is capable of storing multiple routes
            if (result.isSupportsMultipleRoutes())
                continue;

            // result from GPSBabel-based format which allows only one route but is represented by GPX 1.0
            if (result instanceof BabelFormat)
                continue;

            // default for multiple routes is GPX 1.1
            result = new Gpx11Format();
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void commentRoutes(List<BaseRoute> routes) {
        commentRoutePositions(routes);
        for (BaseRoute<BaseNavigationPosition, BaseNavigationFormat> route : routes) {
            commentRouteName(route);
        }
    }

    @SuppressWarnings("unchecked")
    private void commentRoute(BaseRoute route) {
        commentPositions(route.getPositions());
        commentRouteName(route);
    }

    @SuppressWarnings("unchecked")
    private ParserResult createResult(ParserContext<BaseRoute> context) throws IOException {
        List<BaseRoute> source = context.getRoutes();
        // if (source != null && source.size() > 0) {
        if (source != null && context.getFormats().size() > 0) {
            NavigationFormat format = determineFormat(source, context.getFormats().get(0));
            List<BaseRoute> destination = convertRoute(source, format);
            log.info("Detected '" + format.getName() + "' with " + destination.size() + " route(s) and " +
                    getPositionCounts(destination) + " positions");
            if (destination.size() == 0)
                destination.add(format.createRoute(RouteCharacteristics.Route, null, new ArrayList<>()));
            commentRoutes(destination);
            return new ParserResult(new FormatAndRoutes(format, destination));
        } else
            return new ParserResult(null);
    }

    private class InternalParserContext<R extends BaseRoute> extends ParserContextImpl<R> {
        InternalParserContext(File file, CompactCalendar startDate) {
            super(file, startDate);
        }

        public void parse(InputStream inputStream, CompactCalendar startDate, String preferredExtension) throws IOException {
            internalSetStartDate(startDate);
            internalRead(inputStream, getNavigationFormatRegistry().getReadFormatsPreferredByExtension(preferredExtension), this);
        }

        public void parse(String urlString) throws IOException {
            // replace CWD with current working directory for easier testing
            urlString = urlString.replace("CWD", new File(".").getCanonicalPath()).replace(separatorChar, '/');
            URL url = new URL(urlString);
            int readBufferSize = getSize(url);
            log.info("Reading '" + url + "' with a buffer of " + readBufferSize + " bytes");
            NotClosingUnderlyingInputStream buffer = new NotClosingUnderlyingInputStream(new BufferedInputStream(openStream(url), CHUNK_BUFFER_SIZE));
            // make sure not to read a byte after the limit
            buffer.mark(readBufferSize + CHUNK_BUFFER_SIZE * 2);
            try {
                CompactCalendar startDate = extractStartDate(url);
                internalSetStartDate(startDate);
                internalRead(buffer, getNavigationFormatRegistry().getReadFormats(), this);
            } finally {
                buffer.closeUnderlyingInputStream();
            }
        }
    }

    private ParserResult read(InputStream source, int readBufferSize, CompactCalendar startDate, File file,
                              List<NavigationFormat> formats) throws IOException {
        log.fine("Reading '" + source + "' with a buffer of " + readBufferSize + " bytes by " + formats.size() + " formats");
        NotClosingUnderlyingInputStream buffer = new NotClosingUnderlyingInputStream(new BufferedInputStream(source, CHUNK_BUFFER_SIZE));
        // make sure not to read a byte after the limit
        buffer.mark(readBufferSize + CHUNK_BUFFER_SIZE * 2);
        try {
            ParserContext<BaseRoute> context = new InternalParserContext<>(file, startDate);
            internalRead(buffer, formats, context);
            return createResult(context);
        } finally {
            buffer.closeUnderlyingInputStream();
        }
    }

    public ParserResult read(String source) throws IOException {
        return read(new ByteArrayInputStream(source.getBytes()));
    }

    public ParserResult read(InputStream source) throws IOException {
        return read(source, getNavigationFormatRegistry().getReadFormats());
    }

    public ParserResult read(InputStream source, List<NavigationFormat> formats) throws IOException {
        return read(source, TOTAL_BUFFER_SIZE, null, null, formats);
    }

    private int getSize(URL url) throws IOException {
        try {
            if (url.getProtocol().equals("file"))
                return (int) new File(url.toURI()).length();
            else
                return TOTAL_BUFFER_SIZE;
        } catch (URISyntaxException e) {
            throw new IOException("Cannot determine file from URL: " + e);
        }
    }

    private CompactCalendar extractStartDate(File file) {
        Calendar startDate = Calendar.getInstance(UTC);
        startDate.setTimeInMillis(file.lastModified());
        return fromCalendar(startDate);
    }

    private File extractFile(URL url) throws IOException {
        try {
            if (url.getProtocol().equals("file")) {
                return new File(url.toURI());
            } else
                return null;
        } catch (URISyntaxException e) {
            throw new IOException("Cannot determine file from URL: " + e);
        }
    }

    private CompactCalendar extractStartDate(URL url) throws IOException {
        File file = extractFile(url);
        if (file != null) {
            return extractStartDate(file);
        } else
            return null;
    }

    private BaseUrlParsingFormat getUrlParsingFormat(String url) {
        for(BaseUrlParsingFormat format : getNavigationFormatRegistry().getUrlParsingFormats()) {
            if(format.findURL(url) != null)
                return format;
        }
        return null;
    }

    public ParserResult read(URL url, List<NavigationFormat> formats) throws IOException {
        BaseUrlParsingFormat urlParsingFormat = getUrlParsingFormat(url.toExternalForm());
        if(urlParsingFormat != null) {
            List<NavigationFormat> readFormats = new ArrayList<>(formats);
            readFormats.add(0, urlParsingFormat);
            byte[] bytes = url.toExternalForm().getBytes();
            return read(new ByteArrayInputStream(bytes), bytes.length, null, null, readFormats);
        }

        if (isGoogleMapsProfileUrl(url)) {
            url = new URL(url.toExternalForm() + "&output=kml");
            formats = new ArrayList<>(formats);
            formats.add(0, new Kml22Format());
        }

        int readBufferSize = getSize(url);
        log.info("Reading '" + url + "' with a buffer of " + readBufferSize + " bytes");
        return read(openStream(url), readBufferSize, extractStartDate(url), extractFile(url), formats);
    }

   private InputStream openStream(URL url) throws IOException {
        String urlString = url.toExternalForm();
        // make sure HTTPS requests use HTTP Client with it's SSL tweaks
        if (urlString.contains("https://")) {
            Get get = new Get(urlString);
            return get.executeAsStream();
        }
        return url.openStream();
    }

    public ParserResult read(URL url) throws IOException {
        return read(url, getNavigationFormatRegistry().getReadFormatsPreferredByExtension(getExtension(url)));
    }


    public static int getNumberOfFilesToWriteFor(BaseRoute route, NavigationFormat format, boolean duplicateFirstPosition) {
        return ceiling(route.getPositionCount() + (duplicateFirstPosition ? 1 : 0), format.getMaximumPositionCount(), true);
    }

    @SuppressWarnings("unchecked")
    private void write(BaseRoute route, NavigationFormat format,
                       boolean duplicateFirstPosition,
                       boolean ignoreMaximumPositionCount,
                       ParserCallback parserCallback,
                       OutputStream... targets) throws IOException {
        log.info("Writing '" + format.getName() + "' position lists with 1 route and " + route.getPositionCount() + " positions");

        BaseRoute routeToWrite = asFormat(route, format);
        commentRoute(routeToWrite);
        preprocessRoute(routeToWrite, format, duplicateFirstPosition, parserCallback);

        int positionsToWrite = routeToWrite.getPositionCount();
        int writeInOneChunk = format.getMaximumPositionCount();

        // check if the positions to write fit within the given files
        if (positionsToWrite > targets.length * writeInOneChunk) {
            if (ignoreMaximumPositionCount)
                writeInOneChunk = positionsToWrite;
            else
                throw new IOException("Found " + positionsToWrite + " positions, " + format.getName() +
                        " format may only contain " + writeInOneChunk + " positions in one position list.");
        }

        int startIndex = 0;
        for (int i = 0; i < targets.length; i++) {
            OutputStream target = targets[i];
            int endIndex = min(startIndex + writeInOneChunk, positionsToWrite);
            renameRoute(route, routeToWrite, startIndex, endIndex, i, targets);
            format.write(routeToWrite, target, startIndex, endIndex);
            log.info("Wrote position list from " + startIndex + " to " + endIndex);
            startIndex += writeInOneChunk;
        }

        postProcessRoute(routeToWrite, format, duplicateFirstPosition);
    }

    public void write(BaseRoute route, NavigationFormat format, File target) throws IOException {
        write(route, format, false, false, null, target);
    }

    public void write(BaseRoute route, NavigationFormat format,
                      boolean duplicateFirstPosition,
                      boolean ignoreMaximumPositionCount,
                      ParserCallback parserCallback,
                      File... targets) throws IOException {
        OutputStream[] targetStreams = new OutputStream[targets.length];
        for (int i = 0; i < targets.length; i++) {
            // PhotoFormat modifies target in place since it needs the image date,
            // so we don't create a FileOutputStream to avoid zeroing the file
            if (!(format instanceof PhotoFormat))
                targetStreams[i] = new FileOutputStream(targets[i]);
        }
        write(route, format, duplicateFirstPosition, ignoreMaximumPositionCount, parserCallback, targetStreams);
        for (File target : targets)
            log.info("Wrote '" + target.getAbsolutePath() + "'");
    }


    @SuppressWarnings("unchecked")
    private void preprocessRoute(BaseRoute routeToWrite, NavigationFormat format,
                                 boolean duplicateFirstPosition,
                                 ParserCallback parserCallback) {
        if (format instanceof NmnFormat)
            routeToWrite.removeDuplicates();
        if (format instanceof NmnFormat && duplicateFirstPosition) {
            BaseNavigationPosition position = ((NmnFormat) format).getDuplicateFirstPosition(routeToWrite);
            if (position != null)
                routeToWrite.add(0, position);
        }
        if (format instanceof CoPilotFormat && duplicateFirstPosition) {
            BaseNavigationPosition position = ((CoPilotFormat) format).getDuplicateFirstPosition(routeToWrite);
            if (position != null)
                routeToWrite.add(0, position);
        }
        if (format instanceof TcxFormat)
            routeToWrite.ensureIncreasingTime();
        if (parserCallback != null)
            parserCallback.process(routeToWrite, format);
    }

    @SuppressWarnings("unchecked")
    private void renameRoute(BaseRoute route, BaseRoute routeToWrite, int startIndex, int endIndex, int trackIndex, OutputStream... targets) {
        // gives splitted TomTomRoute and SimpleRoute routes a more useful name for the fragment
        if (route.getFormat() instanceof TomTomRouteFormat || route.getFormat() instanceof SimpleFormat ||
                route.getFormat() instanceof GpxFormat && routeToWrite.getFormat() instanceof BcrFormat) {
            String name = createRouteName(routeToWrite.getPositions().subList(startIndex, endIndex));
            if (targets.length > 1)
                name = "Track" + (trackIndex + 1) + ": " + name;
            routeToWrite.setName(name);
        }
    }

    private void postProcessRoute(BaseRoute routeToWrite, NavigationFormat format, boolean duplicateFirstPosition) {
        if ((format instanceof NmnFormat || format instanceof CoPilotFormat) && duplicateFirstPosition)
            routeToWrite.remove(0);
    }


    @SuppressWarnings("unchecked")
    public void write(List<BaseRoute> routes, MultipleRoutesFormat format, File target) throws IOException {
        log.info("Writing '" + format.getName() + "' with " + routes.size() + " routes and " +
                getPositionCounts(routes) + " positions");

        List<BaseRoute> routesToWrite = new ArrayList<>(routes.size());
        for (BaseRoute route : routes) {
            BaseRoute routeToWrite = asFormat(route, format);
            commentRoute(routeToWrite);
            preprocessRoute(routeToWrite, format, false, null);
            routesToWrite.add(routeToWrite);
            postProcessRoute(routeToWrite, format, false);
        }

        try (OutputStream outputStream = new FileOutputStream(target)) {
            format.write(routesToWrite, outputStream);
            log.info("Wrote '" + target.getAbsolutePath() + "'");
        }
    }
}
