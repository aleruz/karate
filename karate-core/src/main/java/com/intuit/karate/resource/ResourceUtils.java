/*
 * The MIT License
 *
 * Copyright 2020 Intuit Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.intuit.karate.resource;

import com.intuit.karate.core.Feature;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ResourceList;
import io.github.classgraph.ScanResult;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author pthomas3
 */
public class ResourceUtils {

    private static final Logger logger = LoggerFactory.getLogger(ResourceUtils.class);

    private ResourceUtils() {
        // only static methods
    }

    public static List<Feature> findFeatureFiles(List<String> paths) {
        List<Feature> features = new ArrayList();
        if (paths == null || paths.isEmpty()) {
            return features;
        }
        if (paths.size() == 1) {
            String path = paths.get(0);
            int pos = path.indexOf(".feature:");
            int line;
            if (pos != -1) { // line number has been appended
                line = Integer.valueOf(path.substring(pos + 9));
                path = path.substring(0, pos + 8);
            } else {
                line = -1;
            }
            if (path.endsWith(".feature")) {
                Resource resource = getResource(path);
                Feature feature = Feature.read(resource);
                feature.setCallLine(line);
                features.add(feature);
                return features;
            }
        }
        Collection<Resource> resources = findResourcesByExtension("feature", paths);
        for (Resource resource : resources) {
            features.add(Feature.read(resource));
        }
        return features;
    }

    public static Resource getResource(String path) {
        if (path.startsWith("classpath:")) {
            path = removePrefix(path);
            File file = classPathToFile(path);
            if (file != null) {
                return new FileResource(file, true, path);
            }
            List<Resource> resources = new ArrayList();
            try (ScanResult scanResult = new ClassGraph().acceptPaths("/").scan()) {
                ResourceList rl = scanResult.getResourcesWithPath(removePrefix(path));
                rl.forEachByteArrayIgnoringIOException((res, bytes) -> {
                    URI uri = res.getURI();
                    if ("file".equals(uri.getScheme())) {
                        File found = Paths.get(uri).toFile();
                        resources.add(new FileResource(found, true, res.getPath()));
                    } else {
                        resources.add(new JarResource(bytes, res.getPath()));
                    }
                });
            }
            if (resources.isEmpty()) {
                throw new RuntimeException("not found: " + path);
            }
            return resources.get(0);
        } else {
            File file = new File(removePrefix(path));
            if (!file.exists()) {
                throw new RuntimeException("not found: " + path);
            }
            return new FileResource(file);
        }
    }

    public static Collection<Resource> findResourcesByExtension(String extension, String path) {
        return findResourcesByExtension(extension, Collections.singletonList(path));
    }

    public static List<Resource> findResourcesByExtension(String extension, List<String> paths) {
        List<Resource> results = new ArrayList();
        List<File> fileRoots = new ArrayList();
        List<String> pathRoots = new ArrayList();
        for (String path : paths) {
            if (path.endsWith("." + extension)) {
                results.add(getResource(path));
            } else if (path.startsWith("classpath:")) {
                pathRoots.add(removePrefix(path));
            } else {
                fileRoots.add(new File(removePrefix(path)));
            }
        }
        if (!fileRoots.isEmpty()) {
            results.addAll(findFilesByExtension(extension, fileRoots));
        } else if (results.isEmpty() && !pathRoots.isEmpty()) {
            String[] searchPaths = pathRoots.toArray(new String[pathRoots.size()]);
            try (ScanResult scanResult = new ClassGraph().acceptPaths(searchPaths).scan()) {
                ResourceList rl = scanResult.getResourcesWithExtension(extension);
                rl.forEachByteArrayIgnoringIOException((res, bytes) -> {
                    URI uri = res.getURI();
                    if ("file".equals(uri.getScheme())) {
                        File file = Paths.get(uri).toFile();
                        results.add(new FileResource(file, true, res.getPath()));
                    } else {
                        results.add(new JarResource(bytes, res.getPath()));
                    }
                });
            }
        }
        return results;
    }

    private static List<Resource> findFilesByExtension(String extension, List<File> files) {
        List<File> results = new ArrayList();
        for (File base : files) {
            Path searchPath = base.toPath();
            Stream<Path> stream;
            try {
                stream = Files.walk(searchPath);
                for (Iterator<Path> paths = stream.iterator(); paths.hasNext();) {
                    Path path = paths.next();
                    String fileName = path.getFileName().toString();
                    if (fileName.endsWith("." + extension)) {
                        results.add(path.toFile());
                    }
                }
            } catch (IOException e) { // NoSuchFileException  
                logger.trace("unable to walk path: {} - {}", searchPath, e.getMessage());
            }
        }
        return results.stream().map(f -> new FileResource(f)).collect(Collectors.toList());
    }

    public static File getFileRelativeTo(Class clazz, String path) {
        Path dirPath = getPathContaining(clazz);
        File file = new File(dirPath + File.separator + path);
        if (file.exists()) {
            return file;
        }
        try {
            URL relativePath = clazz.getClassLoader().getResource(toPathFromClassPathRoot(clazz) + File.separator + path);
            return Paths.get(relativePath.toURI()).toFile();
        } catch (Exception e) {
            throw new RuntimeException("cannot find " + path + " relative to " + clazz + ", " + e.getMessage());
        }
    }

    public static Path getPathContaining(Class clazz) {
        String relative = toPathFromClassPathRoot(clazz);
        URL url = clazz.getClassLoader().getResource(relative);
        try {
            return Paths.get(url.toURI());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static File getDirContaining(Class clazz) {
        Path path = getPathContaining(clazz);
        return path.toFile();
    }

    public static String toPathFromClassPathRoot(Class clazz) {
        Package p = clazz.getPackage();
        String relative = "";
        if (p != null) {
            relative = p.getName().replace('.', '/');
        }
        return relative;
    }

    private static String removePrefix(String text) {
        int pos = text.indexOf(':');
        return pos == -1 ? text : text.substring(pos + 1);
    }

    public static String toPackageQualifiedName(String path) {
        if (path.endsWith(".feature")) {
            path = path.substring(0, path.length() - 8);
        }
        return path.replace('/', '.').replaceAll("\\.[.]+", ".");
    }

    public static InputStream classPathToStream(String path) {
        return ResourceUtils.class.getClassLoader().getResourceAsStream(path);
    }

    public static File classPathToFile(String path) {
        URL url = ResourceUtils.class.getClassLoader().getResource(path);
        if (url == null || !"file".equals(url.getProtocol())) {
            return null;
        }
        try {
            return Paths.get(url.toURI()).toFile();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
