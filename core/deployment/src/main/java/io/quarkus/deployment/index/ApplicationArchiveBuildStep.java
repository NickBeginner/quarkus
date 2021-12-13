package io.quarkus.deployment.index;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Indexer;
import org.jboss.logging.Logger;

import io.quarkus.deployment.ApplicationArchive;
import io.quarkus.deployment.ApplicationArchiveImpl;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.AdditionalApplicationArchiveBuildItem;
import io.quarkus.deployment.builditem.AdditionalApplicationArchiveMarkerBuildItem;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.ApplicationIndexBuildItem;
import io.quarkus.deployment.builditem.ArchiveRootBuildItem;
import io.quarkus.deployment.builditem.IndexDependencyBuildItem;
import io.quarkus.deployment.builditem.LiveReloadBuildItem;
import io.quarkus.deployment.builditem.QuarkusBuildCloseablesBuildItem;
import io.quarkus.deployment.configuration.ClassLoadingConfig;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.fs.util.ZipUtils;
import io.quarkus.maven.dependency.ArtifactCoords;
import io.quarkus.maven.dependency.ArtifactKey;
import io.quarkus.maven.dependency.GACT;
import io.quarkus.maven.dependency.GACTV;
import io.quarkus.maven.dependency.ResolvedDependency;
import io.quarkus.paths.PathCollection;
import io.quarkus.paths.PathList;
import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigDocSection;
import io.quarkus.runtime.annotations.ConfigItem;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;

public class ApplicationArchiveBuildStep {

    private static final Logger LOGGER = Logger.getLogger(ApplicationArchiveBuildStep.class);

    IndexDependencyConfiguration config;

    @ConfigRoot(phase = ConfigPhase.BUILD_TIME)
    static final class IndexDependencyConfiguration {
        /**
         * Artifacts on the classpath that should also be indexed.
         * <p>
         * Their classes will be in the index and processed by Quarkus processors.
         */
        @ConfigItem(name = ConfigItem.PARENT)
        @ConfigDocSection
        @ConfigDocMapKey("dependency-name")
        Map<String, IndexDependencyConfig> indexDependency;
    }

    @BuildStep
    void addConfiguredIndexedDependencies(BuildProducer<IndexDependencyBuildItem> indexDependencyBuildItemBuildProducer) {
        for (IndexDependencyConfig indexDependencyConfig : config.indexDependency.values()) {
            indexDependencyBuildItemBuildProducer.produce(new IndexDependencyBuildItem(indexDependencyConfig.groupId,
                    indexDependencyConfig.artifactId, indexDependencyConfig.classifier.orElse(null)));
        }
    }

    @BuildStep
    ApplicationArchivesBuildItem build(
            QuarkusBuildCloseablesBuildItem buildCloseables,
            ArchiveRootBuildItem root, ApplicationIndexBuildItem appindex,
            List<AdditionalApplicationArchiveMarkerBuildItem> appMarkers,
            List<AdditionalApplicationArchiveBuildItem> additionalApplicationArchiveBuildItem,
            List<IndexDependencyBuildItem> indexDependencyBuildItems,
            LiveReloadBuildItem liveReloadContext,
            CurateOutcomeBuildItem curateOutcomeBuildItem,
            ClassLoadingConfig classLoadingConfig) throws IOException {

        Set<String> markerFiles = new HashSet<>();
        for (AdditionalApplicationArchiveMarkerBuildItem i : appMarkers) {
            markerFiles.add(i.getFile());
        }

        IndexCache indexCache = liveReloadContext.getContextObject(IndexCache.class);
        if (indexCache == null) {
            indexCache = new IndexCache();
            liveReloadContext.setContextObject(IndexCache.class, indexCache);
        }

        Map<ArtifactKey, Set<String>> removedResources = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry : classLoadingConfig.removedResources.entrySet()) {
            removedResources.put(new GACT(entry.getKey().split(":")), entry.getValue());
        }

        List<ApplicationArchive> applicationArchives = scanForOtherIndexes(buildCloseables,
                markerFiles, root, additionalApplicationArchiveBuildItem, indexDependencyBuildItems, indexCache,
                curateOutcomeBuildItem, removedResources);
        return new ApplicationArchivesBuildItem(
                new ApplicationArchiveImpl(appindex.getIndex(), PathList.from(root.getRootDirs()),
                        PathList.from(root.getPaths()), null),
                applicationArchives);
    }

    private List<ApplicationArchive> scanForOtherIndexes(QuarkusBuildCloseablesBuildItem buildCloseables,
            Set<String> applicationArchiveFiles,
            ArchiveRootBuildItem root, List<AdditionalApplicationArchiveBuildItem> additionalApplicationArchives,
            List<IndexDependencyBuildItem> indexDependencyBuildItem, IndexCache indexCache,
            CurateOutcomeBuildItem curateOutcomeBuildItem, Map<ArtifactKey, Set<String>> removedResources)
            throws IOException {

        List<ApplicationArchive> appArchives = new ArrayList<>();
        Set<Path> indexedPaths = new HashSet<>();

        //get paths that are included via marker files
        Set<String> markers = new HashSet<>(applicationArchiveFiles);
        markers.add(IndexingUtil.JANDEX_INDEX);
        addMarkerFilePaths(markers, root, curateOutcomeBuildItem, indexedPaths, appArchives, buildCloseables,
                indexCache, removedResources);

        //get paths that are included via index-dependencies
        addIndexDependencyPaths(indexDependencyBuildItem, root, indexedPaths, appArchives, buildCloseables,
                indexCache, curateOutcomeBuildItem, removedResources);

        for (AdditionalApplicationArchiveBuildItem i : additionalApplicationArchives) {
            for (Path apPath : i.getResolvedPaths()) {
                if (!root.getPaths().contains(apPath) && indexedPaths.add(apPath)) {
                    appArchives.add(createApplicationArchive(buildCloseables, indexCache, apPath, null,
                            removedResources));
                }
            }
        }

        return appArchives;
    }

    private void addIndexDependencyPaths(List<IndexDependencyBuildItem> indexDependencyBuildItems, ArchiveRootBuildItem root,
            Set<Path> indexedDeps, List<ApplicationArchive> appArchives,
            QuarkusBuildCloseablesBuildItem buildCloseables, IndexCache indexCache,
            CurateOutcomeBuildItem curateOutcomeBuildItem,
            Map<ArtifactKey, Set<String>> removedResources) {
        if (indexDependencyBuildItems.isEmpty()) {
            return;
        }
        final Collection<ResolvedDependency> userDeps = curateOutcomeBuildItem.getApplicationModel()
                .getRuntimeDependencies();
        final Map<ArtifactKey, ResolvedDependency> userMap = new HashMap<>(userDeps.size());
        for (ResolvedDependency dep : userDeps) {
            userMap.put(dep.getKey(), dep);
        }
        try {
            for (IndexDependencyBuildItem indexDependencyBuildItem : indexDependencyBuildItems) {
                final ArtifactKey key = new GACT(indexDependencyBuildItem.getGroupId(),
                        indexDependencyBuildItem.getArtifactId(),
                        indexDependencyBuildItem.getClassifier(),
                        GACTV.TYPE_JAR);
                final ResolvedDependency artifact = userMap.get(key);
                if (artifact == null) {
                    throw new RuntimeException(
                            "Could not resolve artifact " + key + " among the runtime dependencies of the application");
                }
                for (Path path : artifact.getResolvedPaths()) {
                    if (!root.isExcludedFromIndexing(path) && !root.getPaths().contains(path) && indexedDeps.add(path)) {
                        appArchives.add(createApplicationArchive(buildCloseables, indexCache, path, key,
                                removedResources));
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static ApplicationArchive createApplicationArchive(QuarkusBuildCloseablesBuildItem buildCloseables,
            IndexCache indexCache, Path dep, ArtifactKey artifactKey, Map<ArtifactKey, Set<String>> removedResources)
            throws IOException {
        Path rootDir = dep;
        boolean isDirectory = Files.isDirectory(dep);
        if (!isDirectory) {
            final FileSystem fs = buildCloseables.add(ZipUtils.newFileSystem(dep));
            rootDir = fs.getRootDirectories().iterator().next();
        }
        final IndexView index = indexPath(indexCache, dep, removedResources.get(artifactKey), isDirectory);
        return new ApplicationArchiveImpl(index, rootDir, dep, artifactKey);
    }

    private static IndexView indexPath(IndexCache indexCache, Path dep, Set<String> removed, boolean isWorkspaceModule)
            throws IOException {
        LOGGER.debugf("Indexing dependency: %s", dep);
        return isWorkspaceModule ? handleFilePath(dep, removed) : handleJarPath(dep, indexCache, removed);
    }

    private static void addMarkerFilePaths(Set<String> applicationArchiveMarkers,
            ArchiveRootBuildItem root, CurateOutcomeBuildItem curateOutcomeBuildItem, Set<Path> indexedPaths,
            List<ApplicationArchive> appArchives, QuarkusBuildCloseablesBuildItem buildCloseables,
            IndexCache indexCache, Map<ArtifactKey, Set<String>> removed)
            throws IOException {

        Set<URI> markedUris = new HashSet<>();
        for (String marker : applicationArchiveMarkers) {
            Enumeration<URL> resources = Thread.currentThread().getContextClassLoader().getResources(marker);

            while (resources.hasMoreElements()) {
                URL resource = resources.nextElement();
                String s = resource.toString();
                if ("jar".equals(resource.getProtocol())) {
                    // Path is in the format  jar:file:/path/to/jarfile.jar!/META-INF/jandex.idx. Only the path to jar
                    // is needed, for comparisons and opening fs. Remove the protocol an path inside jar here.
                    // Results in file:/path/to/jarfile.jar
                    s = s.substring(4, s.lastIndexOf("!"));
                } else if ("file".equals(resource.getProtocol())) {
                    s = s.substring(0, s.length() - marker.length());
                }
                markedUris.add(URI.create(s));
            }
        }

        List<ResolvedDependency> applicationArchives = new ArrayList<>();
        for (ResolvedDependency dep : curateOutcomeBuildItem.getApplicationModel().getRuntimeDependencies()) {
            if (!ArtifactCoords.TYPE_JAR.equals(dep.getType())) {
                continue;
            }

            final PathCollection artifactPaths = dep.getResolvedPaths();
            for (Path p : artifactPaths) {
                if (root.isExcludedFromIndexing(p)) {
                    continue;
                }

                if (markedUris.contains(p.toUri())) {
                    applicationArchives.add(dep);
                    break;
                }
            }
        }

        for (ResolvedDependency dep : applicationArchives) {
            final PathList.Builder rootDirs = PathList.builder();
            final PathCollection artifactPaths = dep.getResolvedPaths();
            final List<IndexView> indexes = new ArrayList<>(artifactPaths.size());
            for (Path p : artifactPaths) {
                boolean isDirectory = Files.isDirectory(p);
                if (isDirectory) {
                    rootDirs.add(p);
                } else {
                    FileSystem fs = ZipUtils.newFileSystem(p);
                    buildCloseables.add(fs);
                    fs.getRootDirectories().forEach(rootDirs::add);
                }
                indexes.add(indexPath(indexCache, p, removed.get(dep.getKey()), isDirectory));
                indexedPaths.add(p);
            }
            appArchives
                    .add(new ApplicationArchiveImpl(indexes.size() == 1 ? indexes.get(0) : CompositeIndex.create(indexes),
                            rootDirs.build(), artifactPaths, dep.getKey()));
        }
    }

    private static Index handleFilePath(Path path, Set<String> removed) throws IOException {
        Indexer indexer = new Indexer();
        try (Stream<Path> stream = Files.walk(path)) {
            stream.forEach(path1 -> {
                if (removed != null) {
                    String relative = path.relativize(path1).toString().replace("\\", "/");
                    if (removed.contains(relative)) {
                        return;
                    }
                }
                if (path1.toString().endsWith(".class")) {
                    try (FileInputStream in = new FileInputStream(path1.toFile())) {
                        indexer.index(in);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
        return indexer.complete();
    }

    private static Index handleJarPath(Path path, IndexCache indexCache, Set<String> removed) {
        return indexCache.cache.computeIfAbsent(path, new Function<Path, Index>() {
            @Override
            public Index apply(Path path) {
                try {
                    return IndexingUtil.indexJar(path, removed);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to process " + path, e);
                }
            }
        });
    }

    /**
     * When running in hot deployment mode we know that java archives will never change, there is no need
     * to re-index them each time. We cache them here to reduce the hot reload time.
     */
    private static final class IndexCache {

        final Map<Path, Index> cache = new HashMap<>();

    }
}
