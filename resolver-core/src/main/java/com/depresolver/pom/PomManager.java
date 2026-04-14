package com.depresolver.pom;

import com.depresolver.github.PullRequestCreator.BumpedDependency;
import com.depresolver.version.VersionComparator;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class PomManager {

    public record PomCoordinates(String groupId, String artifactId, String version) {}

    public PomCoordinates readCoordinates(String pomContent) throws Exception {
        Model model = parse(pomContent);
        String groupId = model.getGroupId() != null ? model.getGroupId()
                : (model.getParent() != null ? model.getParent().getGroupId() : null);
        String version = model.getVersion() != null ? model.getVersion()
                : (model.getParent() != null ? model.getParent().getVersion() : null);
        return new PomCoordinates(groupId, model.getArtifactId(), version);
    }

    public List<BumpedDependency> findBumps(String pomContent, Map<String, String> latestVersions,
                                             Map<String, String> updatedByMap) throws Exception {
        Model model = parse(pomContent);
        Properties props = model.getProperties();
        List<BumpedDependency> bumps = new ArrayList<>();

        // Direct dependencies
        for (Dependency dep : model.getDependencies()) {
            checkDep(dep.getGroupId(), dep.getArtifactId(), resolveVersion(dep.getVersion(), props),
                    latestVersions, updatedByMap, bumps);
        }

        // Managed dependencies
        if (model.getDependencyManagement() != null) {
            for (Dependency dep : model.getDependencyManagement().getDependencies()) {
                checkDep(dep.getGroupId(), dep.getArtifactId(), resolveVersion(dep.getVersion(), props),
                        latestVersions, updatedByMap, bumps);
            }
        }

        // Plugins
        if (model.getBuild() != null) {
            for (Plugin plugin : model.getBuild().getPlugins()) {
                checkDep(plugin.getGroupId(), plugin.getArtifactId(), resolveVersion(plugin.getVersion(), props),
                        latestVersions, updatedByMap, bumps);
            }
            if (model.getBuild().getPluginManagement() != null) {
                for (Plugin plugin : model.getBuild().getPluginManagement().getPlugins()) {
                    checkDep(plugin.getGroupId(), plugin.getArtifactId(), resolveVersion(plugin.getVersion(), props),
                            latestVersions, updatedByMap, bumps);
                }
            }
        }

        // Parent
        Parent parent = model.getParent();
        if (parent != null) {
            checkDep(parent.getGroupId(), parent.getArtifactId(), parent.getVersion(),
                    latestVersions, updatedByMap, bumps);
        }

        return bumps;
    }

    public String applyBumps(String pomContent, List<BumpedDependency> bumps) throws Exception {
        Model model = parse(pomContent);
        Properties props = model.getProperties();

        for (BumpedDependency bump : bumps) {
            String key = bump.groupId() + ":" + bump.artifactId();

            // Update dependencies
            for (Dependency dep : model.getDependencies()) {
                if (matches(dep, bump)) {
                    setVersion(dep, bump.newVersion(), props);
                }
            }
            if (model.getDependencyManagement() != null) {
                for (Dependency dep : model.getDependencyManagement().getDependencies()) {
                    if (matches(dep, bump)) {
                        setVersion(dep, bump.newVersion(), props);
                    }
                }
            }

            // Update plugins
            if (model.getBuild() != null) {
                for (Plugin plugin : model.getBuild().getPlugins()) {
                    if (matchesPlugin(plugin, bump)) {
                        setPluginVersion(plugin, bump.newVersion(), props);
                    }
                }
                if (model.getBuild().getPluginManagement() != null) {
                    for (Plugin plugin : model.getBuild().getPluginManagement().getPlugins()) {
                        if (matchesPlugin(plugin, bump)) {
                            setPluginVersion(plugin, bump.newVersion(), props);
                        }
                    }
                }
            }

            // Update parent
            if (model.getParent() != null && bump.groupId().equals(model.getParent().getGroupId())
                    && bump.artifactId().equals(model.getParent().getArtifactId())) {
                model.getParent().setVersion(bump.newVersion());
            }
        }

        return serialize(model);
    }

    private void checkDep(String groupId, String artifactId, String currentVersion,
                          Map<String, String> latestVersions, Map<String, String> updatedByMap,
                          List<BumpedDependency> bumps) {
        if (groupId == null || artifactId == null || currentVersion == null) return;
        String key = groupId + ":" + artifactId;
        String latestVersion = latestVersions.get(key);
        if (latestVersion != null && VersionComparator.isOlderThan(currentVersion, latestVersion)) {
            String updatedBy = updatedByMap.getOrDefault(key, "unknown");
            bumps.add(new BumpedDependency(groupId, artifactId, currentVersion, latestVersion, updatedBy));
        }
    }

    private boolean matches(Dependency dep, BumpedDependency bump) {
        return bump.groupId().equals(dep.getGroupId()) && bump.artifactId().equals(dep.getArtifactId());
    }

    private boolean matchesPlugin(Plugin plugin, BumpedDependency bump) {
        return bump.groupId().equals(plugin.getGroupId()) && bump.artifactId().equals(plugin.getArtifactId());
    }

    private void setVersion(Dependency dep, String newVersion, Properties props) {
        if (isPropertyRef(dep.getVersion())) {
            props.setProperty(extractPropertyKey(dep.getVersion()), newVersion);
        } else {
            dep.setVersion(newVersion);
        }
    }

    private void setPluginVersion(Plugin plugin, String newVersion, Properties props) {
        if (isPropertyRef(plugin.getVersion())) {
            props.setProperty(extractPropertyKey(plugin.getVersion()), newVersion);
        } else {
            plugin.setVersion(newVersion);
        }
    }

    private String resolveVersion(String version, Properties props) {
        if (version == null) return null;
        if (isPropertyRef(version)) {
            return props.getProperty(extractPropertyKey(version), version);
        }
        return version;
    }

    private boolean isPropertyRef(String value) {
        return value != null && value.startsWith("${") && value.endsWith("}");
    }

    private String extractPropertyKey(String value) {
        return value.substring(2, value.length() - 1);
    }

    private Model parse(String pomContent) throws Exception {
        return new MavenXpp3Reader().read(new StringReader(pomContent));
    }

    private String serialize(Model model) throws Exception {
        StringWriter writer = new StringWriter();
        new MavenXpp3Writer().write(writer, model);
        return writer.toString();
    }
}
