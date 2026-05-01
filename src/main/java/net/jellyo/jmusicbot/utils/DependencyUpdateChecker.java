/*
 * Copyright 2026 JellyoMusicBot contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jagrosh.jmusicbot.utils;

import com.jagrosh.jmusicbot.JMusicBot;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Checks high-risk runtime dependencies that tend to break as Discord and
 * YouTube change their APIs.
 */
public final class DependencyUpdateChecker
{
    private final static Logger LOG = LoggerFactory.getLogger(DependencyUpdateChecker.class);
    private final static OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();
    private final static String PROJECT_POM_RESOURCE = "/META-INF/maven/net.jellyo/JMusicBot/pom.xml";

    private final static List<TrackedDependency> TRACKED_DEPENDENCIES = Collections.unmodifiableList(Arrays.asList(
            new TrackedDependency("Discord API", "net.dv8tion", "JDA", "https://repo.maven.apache.org/maven2"),
            new TrackedDependency("JDA command utilities", "pw.chew", "jda-chewtils-command", "https://m2.chew.pro/releases"),
            new TrackedDependency("Discord DAVE adapter", "moe.kyokobot.libdave", "adapter-jda", "https://maven.lavalink.dev/snapshots"),
            new TrackedDependency("Lavaplayer", "dev.arbjerg", "lavaplayer", "https://repo.maven.apache.org/maven2"),
            new TrackedDependency("YouTube source manager", "dev.lavalink.youtube", "common", "https://maven.lavalink.dev/releases"),
            new TrackedDependency("YouTube playlist rotator", "dev.arbjerg", "lavaplayer-ext-youtube-rotator", "https://repo.maven.apache.org/maven2"),
            new TrackedDependency("Extra source managers", "com.dunctebot", "sourcemanagers", "https://m2.duncte123.dev/releases")
    ));

    private DependencyUpdateChecker()
    {
    }

    public static List<DependencyUpdate> checkForUpdates()
    {
        Map<String, String> currentVersions = loadProjectDependencyVersions();
        if(currentVersions.isEmpty())
        {
            LOG.debug("Skipping dependency update check because project dependency versions could not be loaded");
            return Collections.emptyList();
        }

        List<DependencyUpdate> updates = new ArrayList<>();
        for(TrackedDependency dependency : TRACKED_DEPENDENCIES)
        {
            String currentVersion = currentVersions.get(dependency.coordinates());
            if(currentVersion == null || currentVersion.trim().isEmpty())
            {
                LOG.debug("Skipping dependency update check for {} because it is not declared in the project POM", dependency.coordinates());
                continue;
            }

            try
            {
                String latestVersion = fetchLatestVersion(dependency);
                if(latestVersion != null && isNewerDependencyVersion(currentVersion, latestVersion))
                    updates.add(new DependencyUpdate(dependency, currentVersion, latestVersion));
            }
            catch(IOException | ParserConfigurationException | SAXException ex)
            {
                LOG.debug("Failed to check dependency update for {}", dependency.coordinates(), ex);
            }
        }
        return updates;
    }

    public static String formatUpdates(List<DependencyUpdate> updates)
    {
        StringBuilder builder = new StringBuilder("Dependency updates are available:\n");
        for(DependencyUpdate update : updates)
        {
            builder.append("- ")
                    .append(update.getName())
                    .append(" (")
                    .append(update.getCoordinates())
                    .append("): ")
                    .append(update.getCurrentVersion())
                    .append(" -> ")
                    .append(update.getLatestVersion())
                    .append("\n  Metadata: ")
                    .append(update.getMetadataUrl())
                    .append('\n');
        }
        builder.append("Update pom.xml and rebuild the shaded jar after reviewing release notes for compatibility.");
        return builder.toString();
    }

    static boolean isNewerDependencyVersion(String currentVersion, String latestVersion)
    {
        if(latestVersion == null || latestVersion.trim().isEmpty())
            return false;
        if(currentVersion != null && latestVersion.trim().equalsIgnoreCase(currentVersion.trim()))
            return false;
        if(looksComparable(currentVersion) && looksComparable(latestVersion))
            return OtherUtil.compareVersions(latestVersion, currentVersion) > 0;
        return true;
    }

    static String parseLatestMavenVersion(String metadata)
            throws IOException, ParserConfigurationException, SAXException
    {
        Document document = parseXml(new ByteArrayInputStream(metadata.getBytes(StandardCharsets.UTF_8)));
        String release = firstText(document, "release");
        if(release != null && !release.trim().isEmpty())
            return release.trim();

        String latest = firstText(document, "latest");
        if(latest != null && !latest.trim().isEmpty())
            return latest.trim();

        NodeList versions = document.getElementsByTagName("version");
        for(int i = versions.getLength() - 1; i >= 0; i--)
        {
            String version = versions.item(i).getTextContent();
            if(version != null && !version.trim().isEmpty())
                return version.trim();
        }
        return null;
    }

    static Map<String, String> parseProjectDependencyVersions(InputStream input)
            throws IOException, ParserConfigurationException, SAXException
    {
        Document document = parseXml(input);
        Map<String, String> properties = readProperties(document);
        Map<String, String> versions = new HashMap<>();

        NodeList dependencies = document.getElementsByTagName("dependency");
        for(int i = 0; i < dependencies.getLength(); i++)
        {
            Node node = dependencies.item(i);
            if(!(node instanceof Element))
                continue;

            Element dependency = (Element) node;
            String groupId = childText(dependency, "groupId");
            String artifactId = childText(dependency, "artifactId");
            String version = resolveProperty(childText(dependency, "version"), properties);
            if(groupId != null && artifactId != null && version != null)
                versions.put(groupId + ":" + artifactId, version);
        }
        return versions;
    }

    private static Map<String, String> loadProjectDependencyVersions()
    {
        try(InputStream input = openProjectPom())
        {
            if(input == null)
                return Collections.emptyMap();
            return parseProjectDependencyVersions(input);
        }
        catch(IOException | ParserConfigurationException | SAXException ex)
        {
            LOG.debug("Failed to load project dependency versions", ex);
            return Collections.emptyMap();
        }
    }

    private static InputStream openProjectPom() throws IOException
    {
        InputStream embedded = JMusicBot.class.getResourceAsStream(PROJECT_POM_RESOURCE);
        if(embedded != null)
            return embedded;

        Path localPom = Paths.get("pom.xml");
        if(Files.isRegularFile(localPom))
            return Files.newInputStream(localPom);

        return null;
    }

    private static String fetchLatestVersion(TrackedDependency dependency)
            throws IOException, ParserConfigurationException, SAXException
    {
        Request request = new Request.Builder()
                .get()
                .url(dependency.metadataUrl())
                .header("User-Agent", "JellyoMusicBot/" + OtherUtil.getCurrentVersion())
                .build();
        try(Response response = HTTP.newCall(request).execute())
        {
            if(!response.isSuccessful())
            {
                LOG.debug("Dependency metadata request failed for {} with HTTP {}", dependency.coordinates(), response.code());
                return null;
            }

            ResponseBody body = response.body();
            if(body == null)
                return null;

            return parseLatestMavenVersion(body.string());
        }
    }

    private static Document parseXml(InputStream input)
            throws IOException, ParserConfigurationException, SAXException
    {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        setFeatureIfSupported(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeatureIfSupported(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(input);
    }

    private static void setFeatureIfSupported(DocumentBuilderFactory factory, String feature, boolean enabled)
            throws ParserConfigurationException
    {
        try
        {
            factory.setFeature(feature, enabled);
        }
        catch(ParserConfigurationException ex)
        {
            LOG.debug("XML parser does not support feature {}", feature, ex);
        }
    }

    private static Map<String, String> readProperties(Document document)
    {
        NodeList propertiesNodes = document.getElementsByTagName("properties");
        if(propertiesNodes.getLength() == 0 || !(propertiesNodes.item(0) instanceof Element))
            return Collections.emptyMap();

        Map<String, String> properties = new HashMap<>();
        NodeList children = propertiesNodes.item(0).getChildNodes();
        for(int i = 0; i < children.getLength(); i++)
        {
            Node node = children.item(i);
            if(node instanceof Element)
                properties.put(nodeName(node), node.getTextContent().trim());
        }
        return properties;
    }

    private static String resolveProperty(String value, Map<String, String> properties)
    {
        if(value == null)
            return null;

        String trimmed = value.trim();
        if(trimmed.startsWith("${") && trimmed.endsWith("}"))
        {
            String key = trimmed.substring(2, trimmed.length() - 1);
            return properties.getOrDefault(key, trimmed);
        }
        return trimmed;
    }

    private static String childText(Element parent, String childName)
    {
        NodeList children = parent.getChildNodes();
        for(int i = 0; i < children.getLength(); i++)
        {
            Node child = children.item(i);
            if(child instanceof Element && childName.equals(nodeName(child)))
                return child.getTextContent().trim();
        }
        return null;
    }

    private static String firstText(Document document, String tagName)
    {
        NodeList nodes = document.getElementsByTagName(tagName);
        if(nodes.getLength() == 0)
            return null;
        return nodes.item(0).getTextContent();
    }

    private static String nodeName(Node node)
    {
        return node.getLocalName() == null ? node.getNodeName() : node.getLocalName();
    }

    private static boolean looksComparable(String version)
    {
        if(version == null)
            return false;
        return version.trim().matches("[vV]?\\d+(\\.\\d+)*(?:[-.](?:a|alpha|b|beta|rc|snapshot)\\d*)?");
    }

    private static final class TrackedDependency
    {
        private final String name;
        private final String groupId;
        private final String artifactId;
        private final String repositoryUrl;

        private TrackedDependency(String name, String groupId, String artifactId, String repositoryUrl)
        {
            this.name = name;
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.repositoryUrl = repositoryUrl;
        }

        private String coordinates()
        {
            return groupId + ":" + artifactId;
        }

        private String metadataUrl()
        {
            String base = repositoryUrl.endsWith("/") ? repositoryUrl : repositoryUrl + "/";
            return base + groupId.replace('.', '/') + "/" + artifactId + "/maven-metadata.xml";
        }
    }

    public static final class DependencyUpdate
    {
        private final TrackedDependency dependency;
        private final String currentVersion;
        private final String latestVersion;

        private DependencyUpdate(TrackedDependency dependency, String currentVersion, String latestVersion)
        {
            this.dependency = dependency;
            this.currentVersion = currentVersion;
            this.latestVersion = latestVersion;
        }

        public String getName()
        {
            return dependency.name;
        }

        public String getCoordinates()
        {
            return dependency.coordinates();
        }

        public String getCurrentVersion()
        {
            return currentVersion;
        }

        public String getLatestVersion()
        {
            return latestVersion;
        }

        public String getMetadataUrl()
        {
            return dependency.metadataUrl();
        }
    }
}
