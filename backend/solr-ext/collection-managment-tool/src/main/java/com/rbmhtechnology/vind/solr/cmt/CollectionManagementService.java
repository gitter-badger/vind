package com.rbmhtechnology.vind.solr.cmt;

import com.google.common.collect.Streams;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrRequest;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.impl.InputStreamResponseParser;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.CollectionAdminRequest.Create;
import org.apache.solr.client.solrj.request.ConfigSetAdminRequest;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.CollectionAdminResponse;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.ContentStreamBase;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

;

/**
 * @author Thomas Kurz (thomas.kurz@redlink.co)
 * @since 29.03.17.
 */
public class CollectionManagementService {

    private static Logger logger = LoggerFactory.getLogger(CollectionManagementService.class);

    private static final String RUNTIME_LIB_FILE_NAME = "runtimelibs.txt";

    private static final int BLOB_STORE_SHARDS = 1;

    private static final int BLOB_STORE_REPLICAS = 1;

    private String zkHost;

    private List<String> repositories = new ArrayList<>();

    private CloudSolrClient client;

    public CollectionManagementService(String zkHost) throws IOException {
        this(zkHost, null);
    }

    public CollectionManagementService(String zkHost, String ... repositories) throws IOException {
        this(repositories);
        this.zkHost = zkHost;
        this.client = new CloudSolrClient(zkHost);

        try {
            NamedList result = this.client.request(new CollectionAdminRequest.ClusterStatus());

            if(((NamedList)((NamedList)result.get("cluster")).get("collections")).get(".system") == null) {
                logger.warn("Blob store '.system' for runtime libs is not yet created. Will create one");

                try {
                    Create create = new CollectionAdminRequest.Create();
                    create.setCollectionName(".system");
                    create.setConfigName(null);
                    create.setNumShards(BLOB_STORE_SHARDS);
                    create.setReplicationFactor(BLOB_STORE_REPLICAS);
                    create.process(client);
                    logger.info("Blob store has been created successfully");
                } catch (SolrServerException e1) {
                    throw new IOException("Blob store is not available and cannot be created");
                }
            }

        } catch (SolrServerException | IOException e) {
            throw new IOException("Cannot ping server", e);
        }
    }

    protected CollectionManagementService(String ... repositories) {
        if(repositories != null && repositories.length > 0) {
            this.repositories = Arrays.asList(repositories);
        } else {
            this.repositories = ConfigurationService.getInstance().getProperties().entrySet().stream().filter(e -> e.getKey().toString().startsWith("repository.")).map(e -> e.getValue().toString()).collect(Collectors.toList());
        }
    }

    /**
     * 1. Check if config set is already deployed on the solr server, if not: download from repo and upload to zK
     * 2. Create collection
     * 3. Check if dependencies (runtime-libs) are installed, if not download and install (and name it with group:artefact:version)
     * 4. Add/Update collection runtime-libs
     *
     * @param collectionName {@link String} name of the collection to create.
     * @param configName should be either the name of an already defined configuration in the solr cloud or the full
     *                   name of an artifact accessible in one of the default repositories.
     * @param numOfShards integer number of shards
     * @param numOfReplicas integer number of replicas
     * @throws {@link IOException} thrown if is not possible to create the collection.
     */
    public void createCollection(String collectionName, String configName, int numOfShards, int numOfReplicas) throws IOException {
        checkAndInstallConfiguration(configName);

        try {
            Create create = new CollectionAdminRequest.Create();
            create.setCollectionName(collectionName);
            create.setConfigName(configName);
            create.setNumShards(numOfShards);
            create.setReplicationFactor(numOfReplicas);
            create.process(client);
        } catch (IOException | SolrServerException e) {
            throw new IOException("Cannot create collection", e);
        }

        Map<String,Long> runtimeDependencies = checkAndInstallRuntimeDependencies(collectionName);

        addOrUpdateRuntimeDependencies(runtimeDependencies, collectionName);
    }

    /**
     * Checks whether a collection is already existing in the solr server.
     * @param collectionName String name of the collection to check.
     * @return true if the collection is already existing in the server false otherwise.
     * @throws {@link IOException} is thrown when a problem with the solr request occurs.
     */
    public boolean collectionExists(String collectionName) throws IOException {
        try {
            final NamedList<Object> request = this.client.request(new CollectionAdminRequest.List());
            return ((List) request.get("collections")).contains(collectionName);
        } catch (SolrServerException | IOException e) {
            throw new IOException("Error during solr request: Cannot get the list of collections", e);
        }
    }

    /**
     * 1. Check if config set is already deployed on the solr server, if not: download from repo and upload to zK
     * 2. Switch configuration
     * 3. Check if dependencies (runtime-libs) are installed, if not download and install (and name it with group:artefact:version)
     * 4. Add/Update collection runtime-libs
     *
     * @param collectionName {@link String} name of the collection to update.
     * @param configName should be either the name of an already defined configuration in the solr cloud or the full
     *                   name of an artifact accessible in one of the default repositories.
     * @throws {@link IOException} is thrown when a problem with the solr request occurs.
     */
    public void updateCollection(String collectionName, String configName) throws IOException {

        final String origConfigName ;
        //final String origMaxShards ;
        //final String origReplicationFactor ;

        try {
            final CollectionAdminResponse status = new CollectionAdminRequest.ClusterStatus()
                    .setCollectionName(collectionName).process(client);
            if(status.getStatus() == 0) {
                origConfigName = (String)((Map) ((SimpleOrderedMap) ((NamedList) status.getResponse().get("cluster")).get("collections")).get(collectionName)).get("configName");
                //origMaxShards = (String)((Map) ((SimpleOrderedMap) ((NamedList) status.getResponse().get("cluster")).get("collections")).get(collectionName)).get("maxShardsPerNode");
                //origReplicationFactor = (String)((Map) ((SimpleOrderedMap) ((NamedList) status.getResponse().get("cluster")).get("collections")).get(collectionName)).get("replicationFactor");
            } else {
                throw new IOException("Unable to get current status of collection [" + collectionName + "]");
            }

        } catch (SolrServerException e) {
            throw new IOException("Unable to get current status of collection [" + collectionName + "]",e);
        }

        //TODO get and remove current runtime libs: Is this really needed?

        //Update or install configuration
        this.checkAndInstallConfiguration(configName, true);
        if(!origConfigName.equals(configName)){

            //Change config set of the collection to the new one
            try (final SolrZkClient zkClient = new SolrZkClient(zkHost, 4000)) {
                //TODO: The following call to the collections API is working from solr >= 6
                /*final SolrQuery modifyCollectionQuery = new SolrQuery();
                modifyCollectionQuery.setRequestHandler("/admin/collections");
                modifyCollectionQuery.set("action", "MODIFYCOLLECTION");
                modifyCollectionQuery.set("collection", collectionName);
                modifyCollectionQuery.set("collection.configName", configName);
                modifyCollectionQuery.set("rule", new String[0]);
                modifyCollectionQuery.set("snitch", new String[0]);
                modifyCollectionQuery.set("maxShardsPerNode", origMaxShards);
                modifyCollectionQuery.set("replicationFactor", origReplicationFactor);
                client.query(modifyCollectionQuery);

                or probably

                final CollectionAdminResponse modifyCollection = new CollectionAdminRequest.ModifyCollection()
                        .setCollectionName(collectionName)
                        create.setConfigName(configName);
                        create.setNumShards(numOfShards);
                        create.setReplicationFactor(numOfReplicas);
                        .process(client);
                */

                // Update link to config set
                ZkController.linkConfSet(zkClient, collectionName, configName);

                //Reload collection
                final CollectionAdminResponse reload = new CollectionAdminRequest.Reload()
                        .setCollectionName(collectionName)
                        .process(client);

                if (!reload.isSuccess()) {
                    throw new IOException("Unable to reload collection [" + collectionName + "]");
                }

            } catch (SolrServerException e) {
                throw new IOException("Unable to update collection [" + collectionName + "]", e);
            } catch (KeeperException | InterruptedException e) {
                throw new IOException("Unable to update collection [" + collectionName+ "]", e);
            }
        }


        final Map<String,Long> updatedRuntimeDependencies = checkAndInstallRuntimeDependencies(collectionName);
        this.addOrUpdateRuntimeDependencies(updatedRuntimeDependencies, collectionName);
    }

    /**
     * Adds or updates runtime dependency to a core
     * @param runtimeDependencies {@link Map} of {@link String} dependency name and its {@link Long} version number.
     * @param collectionName {@link String} name of the collection to update.
     */
    protected void addOrUpdateRuntimeDependencies(Map<String, Long> runtimeDependencies, String collectionName) {
        for(String blobName : runtimeDependencies.keySet()) {
            RuntimeLibRequest request = new RuntimeLibRequest(RuntimeLibRequestType.add, blobName, runtimeDependencies.get(blobName));
            try {
                client.request(request, collectionName);
            } catch (SolrServerException | IOException e) {
                logger.warn("Cannot add runtime dependency {} (v{}) to collection {}", blobName, runtimeDependencies.get(blobName), collectionName); //TODO (minor) parse result
                logger.info("Try to update dependency");
                request.setType(RuntimeLibRequestType.update);

                try {
                    client.request(request, collectionName);
                } catch (SolrServerException | IOException e1) {
                    logger.warn("Cannot update runtime dependency {} (v{}) to collection {}", blobName, runtimeDependencies.get(blobName), collectionName); //TODO (minor) parse result
                }
            }
        }
    }

    protected Map<String,Long> checkAndInstallRuntimeDependencies(String collectionName) {
        //get the list of runtime
        List<String> dependencies = Collections.emptyList();
        try {
            dependencies = listRuntimeDependencies(collectionName);
        } catch (SolrServerException | IOException e) {
            logger.warn("Cannot get runtime dependencies from configuration for collection " + collectionName);
        }

        Map<String,Long> runtimeLibVersions = new HashMap<>();

        //check installed dependencies
        for(String dependency : dependencies) {
            long version = getVersionAndInstallIfNecessary(dependency);
            if(version > -1) {
                runtimeLibVersions.put(Utils.toBlobName(dependency), version);
            }
        }

        return runtimeLibVersions;
    }

    protected List<String> listRuntimeDependencies(String collectionName) throws IOException, SolrServerException {
        ModifiableSolrParams params = new ModifiableSolrParams().set("file",RUNTIME_LIB_FILE_NAME);
        SolrRequest request = new QueryRequest(params);
        request.setPath("/admin/file");
        request.setResponseParser(new InputStreamResponseParser("json"));

        NamedList o = client.request(request, collectionName);

        LineIterator it = IOUtils.lineIterator((InputStream) o.get("stream"), "utf-8");

        List<String> returnValues = Streams.stream(it).collect(Collectors.toList());

        //if file not exists (a little hacky..)
        if(returnValues.size() == 1 && returnValues.get(0).startsWith("{\"responseHeader\":{\"status\":404")) {
            logger.warn("Release does not yet contain rumtimelib configuration file. Runtimelibs have to be installed manually.");
            return Collections.emptyList();
        };
        return returnValues;
    }

    public Long getVersionAndInstallIfNecessary(String dependency) {

        try {
            SolrQuery query = new SolrQuery("blobName:"+Utils.toBlobName(dependency));
            query.setSort("version", SolrQuery.ORDER.desc);
            QueryResponse response = client.query(".system",query);

            if(response.getResults().getNumFound() > 0) { //should could look for updates here as well?

                return Long.valueOf(response.getResults().get(0).get("version").toString());
            } else {
                Path configDirectory = Files.createTempDirectory(dependency);

                Path jarFile = Utils.downloadToTempDir(configDirectory, repositories, dependency);

                return uploadRuntimeLib(dependency, jarFile);
            }

        } catch (SolrServerException | IOException e) {
            logger.warn("Cannot load runtime dependeny " + dependency + ". This may cause runtime issues.");
            return -1L;
        }
    }

    protected Long uploadRuntimeLib(String dependency, Path jarFile) throws IOException {

        JarUploadRequest request = new JarUploadRequest(jarFile, "/blob/" + Utils.toBlobName(dependency));

        try {
            client.request(request, ".system");
            return 1L;
        } catch (SolrServerException | IOException e) {
            throw new IOException("Cannot upload jar file for runtime lib " + dependency);
        }
    }

    protected void checkAndInstallConfiguration(String configName) throws IOException {
        this.checkAndInstallConfiguration(configName, false);
    }

    protected void checkAndInstallConfiguration(String configName, boolean force) throws IOException {
        if(!configurationIsDeployed(configName)) {
            this.installConfiguration(configName);
        } else if (force) {
            this.installConfiguration(configName);
        }
    }

    private void installConfiguration(String configName) throws IOException {
        Path folder = downloadConfiguration(configName);

        try {
            client.uploadConfig(folder, configName);
        } catch (IOException e) {
            throw new IOException("Cannot upload config set " + configName, e);
        }

        try {
            Utils.deleteRecursively(folder);
        } catch (IOException e) {
            logger.warn("Could not delete directory");
        }
    }

    protected boolean configurationIsDeployed(String configName) throws IOException {
        ConfigSetAdminRequest.List list = new ConfigSetAdminRequest.List();
        try {
            return list.process(client).getConfigSets().contains(configName);
        } catch (SolrServerException | IOException e) {
            throw new IOException("Cannot list config sets", e);
        }
    }

    protected Path downloadConfiguration(String configName) throws IOException {

        try {
            Path configDirectory = Files.createTempDirectory(configName);

            Path jarFile = Utils.downloadToTempDir(configDirectory, repositories, configName);

            Path unzipped = Utils.unzipJar(configDirectory, jarFile);

            return Utils.findParentOfFirstMatch(unzipped,"solrconfig.xml");

        } catch (IOException e) {
            throw new IOException("Cannot create temp folder for downloading " + configName, e);
        }

    }

    protected void removeCollection(String collectionName) throws IOException {
        final CollectionAdminRequest.Delete delete = new CollectionAdminRequest.Delete();
        delete.setCollectionName(collectionName);

        try {
            delete.process(this.client);
        } catch (SolrServerException |IOException e) {
            throw new IOException("Error during solr request: Cannot delete collection " + collectionName, e);
        }
    }

    protected void removeConfigSet(String configSetName) throws IOException {
        final ConfigSetAdminRequest.Delete delete =new ConfigSetAdminRequest.Delete();

        try {
            delete.setConfigSetName(configSetName).process(this.client);
        } catch (SolrServerException |IOException e) {
            throw new IOException("Error during solr request: Cannot delete configSet " + configSetName, e);
        }
    }

    /**
     * Some Utils methods
     */
    protected static class Utils {

        private static final HttpClient httpClient = HttpClientBuilder.create().build();

        public static void deleteRecursively(Path path) throws IOException {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }

            });
        }

        public static Path findParentOfFirstMatch(Path path, String filename) throws IOException {
            Set<Path> dirs = new HashSet<>();
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if(file.toFile().getName().equals(filename)) {
                        dirs.add(file.getParent());
                    }
                    return FileVisitResult.CONTINUE;
                }

            });

            if(dirs.size() == 0) throw new IOException("Unzipped directory does not contain any configuration");

            if(dirs.size() > 1) logger.warn("Unzipped directory contains more than onen configurations, taking one randomly");

            return dirs.iterator().next();
        }

        public static Path unzipJar(Path folder, Path jarFile) {

            try(ZipInputStream zipIn = new ZipInputStream(Files.newInputStream(jarFile))) {

                Path dir = Files.createDirectory(Paths.get(folder.toString() + File.separator + "unzipped"));

                ZipEntry entry = zipIn.getNextEntry();
                // iterates over entries in the zip file
                while (entry != null) {
                    String filePath = dir + File.separator + entry.getName();
                    if (!entry.isDirectory()) {
                        // if the entry is a file, extracts it
                        BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
                        byte[] bytesIn = new byte[4096];
                        int read;
                        while ((read = zipIn.read(bytesIn)) != -1) {
                            bos.write(bytesIn, 0, read);
                        }
                        bos.close();
                    } else {
                        // if the entry is a directory, make the directory
                        File _dir = new File(filePath);
                        _dir.mkdir();
                    }
                    zipIn.closeEntry();
                    entry = zipIn.getNextEntry();
                }

                return dir;
            } catch (IOException e) {
                logger.error("Cannot unzip and upload configuration");
                throw new RuntimeException(e);
            }
        }

        private static class  DowloadResponseHandler implements ResponseHandler<Path> {

            private final URI uri;
            private final Path directory;

            public DowloadResponseHandler(URI uri, Path directory) {
                this.uri = uri;
                this.directory = directory;
            }

            @Override
            public Path handleResponse(HttpResponse response) throws ClientProtocolException, IOException {
                if(response.getStatusLine().getStatusCode() == 200) {
                    final Path resultFile = Paths.get(directory.toString(), FilenameUtils.getName(uri.getPath()));
                    Files.copy(response.getEntity().getContent(), resultFile, StandardCopyOption.REPLACE_EXISTING);
                    return resultFile;
                } else {
                    throw new IOException("Unable to retrieve artifact: " + response.getStatusLine().getStatusCode());
                }
            }
        }

        public static Path downloadToTempDir(Path directory, List<String> repositories, String name) throws IOException {
            for(String repository : repositories)
                try {
                    if (repository.matches("https?://.*")) {
                        final URI uri = new URI((repository.endsWith("/") ? repository : (repository + "/")) + nameToPath(name));
                        try {
                            return httpClient.execute(new HttpGet(uri), new DowloadResponseHandler(uri, directory));
                        } catch (IOException e) {
                            logger.warn("Unable to find artifact in repo {}", repository);
                            final URI metadataUri = new URI((repository.endsWith("/") ? repository : ((repository + "/"))) + nameToMetadataPath(name));

                            final HttpResponse metadataResponse = httpClient.execute(new HttpGet(metadataUri));

                            if (metadataResponse.getStatusLine().getStatusCode() == 200) {
                                //Extracting snapshot timestamp and build version from Metadata XML file
                                final DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                                final DocumentBuilder dBuilder;
                                final String timestamp;
                                final String buildNumber;
                                try {
                                    dBuilder = dbFactory.newDocumentBuilder();
                                    final Document doc = dBuilder.parse(metadataResponse.getEntity().getContent());
                                    doc.getDocumentElement().normalize();
                                    timestamp = doc.getElementsByTagName("timestamp").item(0).getFirstChild().getNodeValue();
                                    buildNumber = doc.getElementsByTagName("buildNumber").item(0).getFirstChild().getNodeValue();

                                } catch (ParserConfigurationException | SAXException pe) {
                                    throw new IOException("Error while parsing artifact XML metadata file", pe);
                                }

                                final URI timeStampUri = new URI((repository.endsWith("/") ? repository : (repository + "/")) + snapshotPath(name, timestamp, buildNumber));
                                try {
                                    return httpClient.execute(new HttpGet(timeStampUri), new DowloadResponseHandler(timeStampUri, directory));
                                } catch (IOException ex) {
                                    logger.warn("{} cannot be found in {}", name, repository);
                                }
                            } else {
                                logger.warn("{} cannot be found in {}", name, repository);
                            }
                            EntityUtils.consume(metadataResponse.getEntity());
                        }
                    } else {
                        Path path = Paths.get(repository, nameToPath(name)).toAbsolutePath();
                        if (Files.exists(path)) {
                            Path resultFile = Paths.get(directory.toString(), path.getFileName().toString());
                            Files.copy(path, resultFile, StandardCopyOption.REPLACE_EXISTING);
                            return resultFile;
                        }
                    }

                } catch (URISyntaxException e) {
                    throw new IOException("Cannot get download location for " + name);
                }

            throw new IOException("Cannot get file for " +name);
        }

        public static String nameToPath(String name) throws IOException {
            String[] split = name.split(":");

            if(split.length != 3) throw new IOException("Cannot get dowload path for " + name);

            return Paths.get(split[0].replaceAll("\\.","/"),split[1],split[2],split[1]+"-"+split[2]+".jar").toFile().getPath();
        }

        public static String nameToMetadataPath(String name) throws IOException {
            String[] split = name.split(":");

            if(split.length != 3) throw new IOException("Cannot get dowload path for " + name);

            return Paths.get(split[0].replaceAll("\\.","/"),split[1],split[2],"maven-metadata.xml").toFile().getPath();
        }

        public static String snapshotPath(String name,String timestamp, String buildNumber) throws IOException {
            String[] split = name.split(":");

            if(split.length != 3) throw new IOException("Cannot get dowload path for " + name);

            return Paths.get(split[0].replaceAll("\\.","/"),split[1],split[2],split[1]+"-"+split[2].replace("-SNAPSHOT","")+"-"+timestamp+"-"+buildNumber+".jar").toFile().getPath();
        }

        public static String toBlobName(String dependency) {
            return dependency.replaceAll(":","_");
        }

    }

    protected class JarUploadRequest extends QueryRequest {

        private Path jarFile;
        private String path;

        public JarUploadRequest(Path jarFile, String path) {
            this.jarFile = jarFile;
            this.path = path;
            this.setMethod(METHOD.POST);
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public Collection<ContentStream> getContentStreams() {
            return Collections.singleton(new ContentStreamBase.FileStream(jarFile.toFile()));
        }
    }

    public enum RuntimeLibRequestType {
        add,update
    }

    protected class RuntimeLibRequest extends QueryRequest {

        private RuntimeLibRequestType type;
        private String blobName;
        private Long version;

        public RuntimeLibRequest(RuntimeLibRequestType type, String blobName, Long version) {
            this.setMethod(METHOD.POST);
            this.type = type;
            this.blobName = blobName;
            this.version = version;
        }

        @Override
        public String getPath() {
            return "/config";
        }

        @Override
        public Collection<ContentStream> getContentStreams() {
            return ClientUtils.toContentStreams(String.format("{\"%s-runtimelib\": { \"name\":\"%s\", \"version\":%s }}", type, blobName, version), ContentType.APPLICATION_JSON.toString());
        }

        public void setType(RuntimeLibRequestType type) {
            this.type = type;
        }
    }

    /**
     * Just for test usage!
     * @param client cloud solr client to connec to to.
     */
    protected void setClient(CloudSolrClient client) {
        this.client = client;
    }

    protected List<String> getRepositories() {
        return repositories;
    }
}
