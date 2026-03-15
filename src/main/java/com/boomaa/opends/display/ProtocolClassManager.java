package com.boomaa.opends.display;

import com.boomaa.opends.display.frames.MessageBox;
import com.boomaa.opends.util.ArrayUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

public class ProtocolClassManager<T extends ProtocolClass> {
    public static final String RANGED_CLAZZ_NAME_SEPARATOR = "to";
    private final String simpleBaseName;
    private final Map<Integer, Class<?>> protoYearClassMap;
    private int year = -1;

    public ProtocolClassManager(Class<? super T> baseClass) {
        this.simpleBaseName = baseClass.getSimpleName();
        Map<String, Class<?>> yearStrClassMap = extractYearStrClassMap(baseClass.getPackage().getName());
        this.protoYearClassMap = expandYearRanges(yearStrClassMap);
    }

    private Map<String, Class<?>> extractYearStrClassMap(String canonicalPkgName) {
        String pkgPath = canonicalPkgName.replace('.', '/');
        Map<String, Class<?>> yearStrClassMap = new HashMap<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = ClassLoader.getSystemClassLoader();
        }
        try {
            Enumeration<URL> resources = classLoader.getResources(pkgPath);
            while (resources.hasMoreElements()) {
                URL resourceUrl = resources.nextElement();
                String protocol = resourceUrl.getProtocol();
                if ("file".equals(protocol)) {
                    String decodedPath = URLDecoder.decode(resourceUrl.getPath(), StandardCharsets.UTF_8.name());
                    Path dirPath = Paths.get(decodedPath);
                    if (!Files.isDirectory(dirPath)) {
                        continue;
                    }
                    try (java.util.stream.Stream<Path> fileStream = Files.list(dirPath)) {
                        fileStream
                                .map(Path::getFileName)
                                .map(Path::toString)
                                .filter(this::isProtocolClassFile)
                                .map(classFile -> classFile.replace(".class", ""))
                                .map(clazzSimpleName -> canonicalPkgName + "." + clazzSimpleName)
                                .map(this::classStrToObj)
                                .filter(Objects::nonNull)
                                .forEach(clazz -> yearStrClassMap.put(protoClassToYearStr(clazz), clazz));
                    } catch (IOException ignored) {
                    }
                } else if ("jar".equals(protocol)) {
                    JarURLConnection jarConnection = (JarURLConnection) resourceUrl.openConnection();
                    try (JarFile jarFile = jarConnection.getJarFile()) {
                        Enumeration<JarEntry> entries = jarFile.entries();
                        while (entries.hasMoreElements()) {
                            JarEntry entry = entries.nextElement();
                            String entryName = entry.getName();
                            if (!entryName.startsWith(pkgPath + "/")) {
                                continue;
                            }
                            String classFileName = entryName.substring(entryName.lastIndexOf('/') + 1);
                            if (!isProtocolClassFile(classFileName)) {
                                continue;
                            }
                            String classCanonicalName = canonicalPkgName + "." + classFileName.replace(".class", "");
                            Class<?> clazz = classStrToObj(classCanonicalName);
                            if (clazz != null) {
                                yearStrClassMap.put(protoClassToYearStr(clazz), clazz);
                            }
                        }
                    }
                }
            }
            if (!yearStrClassMap.isEmpty()) {
                return yearStrClassMap;
            }
            InputStream pkgStream = classLoader.getResourceAsStream(pkgPath);
            if (pkgStream != null) {
                try (BufferedReader br = readerOf(pkgStream)) {
                    Map<String, Class<?>> streamClasses = br.lines()
                            .filter(this::isProtocolClassFile)
                            .map(line -> line.replace(".class", ""))
                            .map(clazzSimpleName -> canonicalPkgName + "." + clazzSimpleName)
                            .map(this::classStrToObj)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toMap(this::protoClassToYearStr, clazz -> clazz));
                    yearStrClassMap.putAll(streamClasses);
                }
                if (!yearStrClassMap.isEmpty()) {
                    return yearStrClassMap;
                }
            }
            return extractKnownProtocolClassMap(canonicalPkgName);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
        return null;
    }

    private boolean isProtocolClassFile(String classFileName) {
        return classFileName.endsWith(".class")
                && classFileName.startsWith(simpleBaseName)
                && !classFileName.contains("$");
    }

    private Map<String, Class<?>> extractKnownProtocolClassMap(String canonicalPkgName) {
        Map<String, Class<?>> known = new HashMap<>();
        Integer[] knownYears = Arrays.copyOf(DisplayEndpoint.VALID_PROTOCOL_YEARS, DisplayEndpoint.VALID_PROTOCOL_YEARS.length);
        for (int protoYear : knownYears) {
            String singleYearClassName = canonicalPkgName + "." + simpleBaseName + protoYear;
            Class<?> singleYearClass = classStrToObj(singleYearClassName);
            if (singleYearClass != null) {
                known.put(protoClassToYearStr(singleYearClass), singleYearClass);
            }
        }
        for (int rangeStartYear : knownYears) {
            for (int rangeEndYear : knownYears) {
                if (rangeStartYear > rangeEndYear) {
                    continue;
                }
                String rangedClassName = canonicalPkgName + "." + simpleBaseName
                        + rangeStartYear + RANGED_CLAZZ_NAME_SEPARATOR + rangeEndYear;
                Class<?> rangedClass = classStrToObj(rangedClassName);
                if (rangedClass != null) {
                    known.put(protoClassToYearStr(rangedClass), rangedClass);
                }
            }
        }
        return known;
    }

    private BufferedReader readerOf(InputStream is) {
        return new BufferedReader(new InputStreamReader(Objects.requireNonNull(is)));
    }

    private Class<?> classStrToObj(String clazzCanonicalName) {
        try {
            return Class.forName(clazzCanonicalName);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    private String protoClassToYearStr(Class<?> clazz) {
        return clazz.getSimpleName().replaceAll(simpleBaseName, "");
    }

    public Map<Integer, Class<?>> expandYearRanges(Map<String, Class<?>> yearStrClassMap) {
        Map<Integer, Class<?>> yearClassMap = new HashMap<>();
        for (Map.Entry<String, Class<?>> entry : yearStrClassMap.entrySet()) {
            String protoYearOrRange = entry.getKey();
            Class<?> protoClass = entry.getValue();
            try {
                int protoYear = Integer.parseInt(protoYearOrRange);
                yearClassMap.put(protoYear, protoClass);
            } catch (NumberFormatException ignored) {
                try {
                    String[] rangeBoundYears = protoYearOrRange.split(RANGED_CLAZZ_NAME_SEPARATOR);
                    if (rangeBoundYears.length != 2) {
                        continue;
                    }
                    int rangeStartYear = Integer.parseInt(rangeBoundYears[0]);
                    int rangeEndYear = Integer.parseInt(rangeBoundYears[1]);
                    for (int protoYear = rangeStartYear; protoYear <= rangeEndYear; protoYear++) {
                        yearClassMap.put(protoYear, protoClass);
                    }
                } catch (NumberFormatException ignored2) {
                }
            }
        }
        return yearClassMap;
    }

    public ProtocolClassManager<T> update() {
        this.year = MainJDEC.getProtocolYear();
        return this;
    }

    public Class<?> getProtoClass() {
        if (year == -1) {
            update();
        }
        return protoYearClassMap.get(this.year);
    }

    public T construct() {
        try {
            return (T) getProtoClass().getConstructor().newInstance();
        } catch (NoSuchMethodException | IllegalAccessException | InstantiationException
                 | InvocationTargetException e) {
            e.printStackTrace();
            MessageBox.show(ArrayUtils.printStackTrace(e, 10), MessageBox.Type.ERROR);
            System.exit(1);
        }
        return null;
    }

    @Override
    public String toString() {
        return getProtoClass().getCanonicalName();
    }
}