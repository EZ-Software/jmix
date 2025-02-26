/*
 * Copyright 2000-2023 Vaadin Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.jmix.flowui.devserver.frontend;

import com.vaadin.experimental.FeatureFlags;
import com.vaadin.flow.server.Constants;
import com.vaadin.flow.server.frontend.FallibleCommand;
import com.vaadin.flow.server.frontend.FrontendVersion;
import com.vaadin.flow.server.frontend.scanner.ClassFinder;
import com.vaadin.flow.server.frontend.scanner.FrontendDependencies;
import com.vaadin.flow.server.frontend.scanner.FrontendDependenciesScanner;
import elemental.json.Json;
import elemental.json.JsonException;
import elemental.json.JsonObject;
import elemental.json.JsonValue;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.vaadin.flow.server.Constants.PACKAGE_JSON;
import static com.vaadin.flow.server.Constants.PACKAGE_LOCK_JSON;
import static elemental.json.impl.JsonUtil.stringify;
import static io.jmix.flowui.devserver.frontend.FrontendUtils.NODE_MODULES;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Base abstract class for frontend updaters that needs to be run when in
 * dev-mode or from the flow maven plugin.
 */
public abstract class NodeUpdater implements FallibleCommand {

    private static final String VAADIN_FORM_PKG_LEGACY_VERSION = "flow-frontend/form";

    private static final String VAADIN_FORM_PKG = "@vaadin/form";

    // .vaadin/vaadin.json contains local installation data inside node_modules
    // This will help us know to execute even when another developer has pushed
    // a new hash to the code repository.
    private static final String VAADIN_JSON = ".vaadin/vaadin.json";

    static final String DEPENDENCIES = "dependencies";
    static final String VAADIN_DEP_KEY = "vaadin";
    static final String HASH_KEY = "hash";
    static final String DEV_DEPENDENCIES = "devDependencies";
    static final String OVERRIDES = "overrides";

    private static final String DEP_LICENSE_KEY = "license";
    private static final String DEP_LICENSE_DEFAULT = "UNLICENSED";
    private static final String DEP_NAME_KEY = "name";
    private static final String DEP_NAME_DEFAULT = "no-name";
    private static final String FRONTEND_RESOURCES_PATH = NodeUpdater.class
            .getPackage().getName().replace('.', '/') + "/";
    @Deprecated
    protected static final String DEP_NAME_FLOW_DEPS = "@vaadin/flow-deps";
    @Deprecated
    protected static final String DEP_NAME_FLOW_JARS = "@vaadin/flow-frontend";

    static final String VAADIN_VERSION = "vaadinVersion";
    static final String PROJECT_FOLDER = "projectFolder";

    /**
     * The {@link FrontendDependencies} object representing the application
     * dependencies.
     */
    protected final FrontendDependenciesScanner frontDeps;

    final ClassFinder finder;

    boolean modified;

    JsonObject versionsJson;

    protected Options options;

    /**
     * Constructor.
     *
     * @param frontendDependencies
     *            a reusable frontend dependencies
     * @param options
     *            the task options
     */
    protected NodeUpdater(FrontendDependenciesScanner frontendDependencies,
            Options options) {
        this.finder = options.getClassFinder();
        this.frontDeps = frontendDependencies;
        this.options = options;
    }

    protected File getProjectJsonFile() {
        return new File(options.getNpmFolder(), PACKAGE_JSON);
    }

    protected File getStudioJsonFile() {
        return new File(options.getStudioFolder(), PACKAGE_JSON);
    }

    protected File getProjectPackageLockFile() {
        return new File(options.getNpmFolder(), PACKAGE_LOCK_JSON);
    }

    protected File getStudioPackageLockFile() {
        return new File(options.getStudioFolder(), PACKAGE_LOCK_JSON);
    }

    /**
     * Gets the platform pinned versions that are not overridden by the user in
     * package.json.
     *
     * @return {@code JsonObject} with the dependencies or empty
     *         {@code JsonObject} if file doesn't exist
     * @throws IOException
     *             when versions file could not be read
     */
    JsonObject getPlatformPinnedDependencies() throws IOException {
        URL coreVersionsResource = FrontendUtils
                .getResource(Constants.VAADIN_CORE_VERSIONS_JSON);
        if (coreVersionsResource == null) {
            String message = String.format(
                    "Couldn't find %s file to pin dependency versions for core components."
                            + " Transitive dependencies won't be pinned for npm/pnpm.",
                    Constants.VAADIN_CORE_VERSIONS_JSON
            );
            log().info(message);
            return Json.createObject();
        }

        JsonObject versionsJson = getFilteredVersionsFromResource(
                coreVersionsResource, Constants.VAADIN_CORE_VERSIONS_JSON);

        URL vaadinVersionsResource = FrontendUtils
                .getResource(Constants.VAADIN_VERSIONS_JSON);
        if (vaadinVersionsResource == null) {
            // vaadin is not on the classpath, only vaadin-core is present.
            return versionsJson;
        }

        JsonObject vaadinVersionsJson = getFilteredVersionsFromResource(
                vaadinVersionsResource, Constants.VAADIN_VERSIONS_JSON);
        for (String key : vaadinVersionsJson.keys()) {
            versionsJson.put(key, vaadinVersionsJson.getString(key));
        }

        return versionsJson;
    }

    private JsonObject getFilteredVersionsFromResource(URL versionsResource,
                                                       String versionsOrigin) throws IOException {
        JsonObject versionsJson;
        try (InputStream content = versionsResource.openStream()) {
            VersionsJsonConverter convert = new VersionsJsonConverter(
                    Json.parse(
                            IOUtils.toString(content, StandardCharsets.UTF_8)),
                    options.isReactEnabled()
                            && FrontendUtils.isReactModuleAvailable(options));
            versionsJson = convert.getConvertedJson();
            versionsJson = new VersionsJsonFilter(getPackageJson(getStudioJsonFile()),
                    DEPENDENCIES)
                    .getFilteredVersions(versionsJson, versionsOrigin);
        }
        return versionsJson;
    }

    static Set<String> getGeneratedModules(File frontendFolder) {
        final Function<String, String> unixPath = str -> str.replace("\\", "/");

        File generatedImportsFolder = FrontendUtils
                .getFlowGeneratedFolder(frontendFolder);
        File webComponentsFolder = FrontendUtils
                .getFlowGeneratedWebComponentsFolder(frontendFolder);
        final URI baseDir = generatedImportsFolder.toURI();

        if (!webComponentsFolder.exists()) {
            return Collections.emptySet();
        }

        return FileUtils
                .listFiles(webComponentsFolder, new String[] { "js" }, true)
                .stream()
                .map(file -> unixPath
                        .apply(baseDir.relativize(file.toURI()).getPath()))
                .collect(Collectors.toSet());
    }

    JsonObject getPackageJson(File packageJsonFile) throws IOException {
        JsonObject packageJson = getJsonFileContent(packageJsonFile);
        if (packageJson == null) {
            packageJson = Json.createObject();
            packageJson.put(DEP_NAME_KEY, DEP_NAME_DEFAULT);
            packageJson.put(DEP_LICENSE_KEY, DEP_LICENSE_DEFAULT);
            packageJson.put("type", "module");
        }

        addDefaultObjects(packageJson);
        addVaadinDefaultsToJson(packageJson);
        removeWebpackPlugins(packageJson);

        return packageJson;
    }

    private void addDefaultObjects(JsonObject json) {
        computeIfAbsent(json, DEPENDENCIES, Json::createObject);
        computeIfAbsent(json, DEV_DEPENDENCIES, Json::createObject);
    }

    private void removeWebpackPlugins(JsonObject packageJson) {
        Path targetFolder = Paths.get(options.getStudioFolder().toString(),
                options.getBuildDirectoryName(),
                FrontendPluginsUtil.PLUGIN_TARGET);

        if (!packageJson.hasKey(DEV_DEPENDENCIES)) {
            return;
        }
        JsonObject devDependencies = packageJson.getObject(DEV_DEPENDENCIES);

        String atVaadinPrefix = "@vaadin/";
        String pluginTargetPrefix = "./"
                + (options.getStudioFolder().toPath().relativize(targetFolder) + "/")
                .replace('\\', '/');

        // Clean previously installed plugins
        for (String depKey : devDependencies.keys()) {
            String depVersion = devDependencies.getString(depKey);
            if (depKey.startsWith(atVaadinPrefix)
                    && depVersion.startsWith(pluginTargetPrefix)) {
                devDependencies.remove(depKey);
            }
        }
    }

    static JsonObject getJsonFileContent(File packageFile) throws IOException {
        JsonObject jsonContent = null;
        if (packageFile.exists()) {
            String fileContent = FileUtils.readFileToString(packageFile,
                    UTF_8.name());
            try {
                jsonContent = Json.parse(fileContent);
            } catch (JsonException e) { // NOSONAR
                throw new JsonException(String
                        .format("Cannot parse package file '%s'", packageFile));
            }
        }
        return jsonContent;
    }

    void addVaadinDefaultsToJson(JsonObject json) {
        JsonObject vaadinPackages = computeIfAbsent(json, VAADIN_DEP_KEY,
                Json::createObject);

        computeIfAbsent(vaadinPackages, DEPENDENCIES, () -> {
            final JsonObject dependencies = Json.createObject();
            getDefaultDependencies().forEach(dependencies::put);
            return dependencies;
        });
        computeIfAbsent(vaadinPackages, DEV_DEPENDENCIES, () -> {
            final JsonObject devDependencies = Json.createObject();
            getDefaultDevDependencies().forEach(devDependencies::put);
            return devDependencies;
        });
        computeIfAbsent(vaadinPackages, HASH_KEY, () -> Json.create(""));
    }

    private static <T extends JsonValue> T computeIfAbsent(
            JsonObject jsonObject, String key, Supplier<T> valueSupplier) {
        T result = jsonObject.get(key);
        if (result == null) {
            result = valueSupplier.get();
            jsonObject.put(key, result);
        }
        return result;
    }

    Map<String, String> getDefaultDependencies() {
        Map<String, String> dependencies = readDependencies("default",
                "dependencies");
        if (options.isReactEnabled()) {
            dependencies
                    .putAll(readDependencies("react-router", "dependencies"));
        } else {
            dependencies
                    .putAll(readDependencies("vaadin-router", "dependencies"));
        }
        putHillaComponentsDependencies(dependencies, "dependencies");
        return dependencies;
    }

    Map<String, String> readDependencies(String id, String packageJsonKey) {
        try {
            Map<String, String> map = new HashMap<>();
            JsonObject dependencies = readPackageJson(id)
                    .getObject(packageJsonKey);
            if (dependencies == null) {
                log().warn("Unable to find " + packageJsonKey + " from '" + id + "'");
                return new HashMap<>();
            }
            for (String key : dependencies.keys()) {
                map.put(key, dependencies.getString(key));
            }

            return map;
        } catch (IOException e) {
            log().error(
                    "Unable to read " + packageJsonKey + " from '" + id + "'",
                    e);
            return new HashMap<>();
        }

    }

    JsonObject readPackageJson(String id) throws IOException {
        URL resource = FrontendUtils.getResource("dependencies/" + id
                        + "/package.json");
        if (resource == null) {
            log().error("Unable to find package.json from '" + id + "'");

            return Json.parse("{\"%s\":{},\"%s\":{}}".formatted(DEPENDENCIES,
                    DEV_DEPENDENCIES));
        }
        return Json.parse(IOUtils.toString(resource, StandardCharsets.UTF_8));
    }

    boolean hasPackageJson(String id) {
        return FrontendUtils.getResource("dependencies/" + id + "/package.json") != null;
    }

    Map<String, String> readDependenciesIfAvailable(String id,
            String packageJsonKey) {
        if (hasPackageJson(id)) {
            return readDependencies(id, packageJsonKey);
        }
        return new HashMap<>();
    }

    Map<String, String> getDefaultDevDependencies() {
        Map<String, String> defaults = new HashMap<>();
        defaults.putAll(readDependencies("default", "devDependencies"));
        defaults.putAll(readDependencies("vite", "devDependencies"));
        putHillaComponentsDependencies(defaults, "devDependencies");
        if (options.isReactEnabled()) {
            defaults.putAll(
                    readDependencies("react-router", "devDependencies"));
        }

        return defaults;
    }

    /**
     * Updates default dependencies and development dependencies to
     * package.json.
     *
     * @param packageJson
     *            package.json json object to update with dependencies
     * @return true if items were added or removed from the {@code packageJson}
     */
    boolean updateDefaultDependencies(JsonObject packageJson) {
        int added = 0;

        for (Map.Entry<String, String> entry : getDefaultDependencies()
                .entrySet()) {
            added += addDependency(packageJson, DEPENDENCIES, entry.getKey(),
                    entry.getValue());
        }

        for (Map.Entry<String, String> entry : getDefaultDevDependencies()
                .entrySet()) {
            added += addDependency(packageJson, DEV_DEPENDENCIES,
                    entry.getKey(), entry.getValue());
        }

        if (added > 0) {
            String message = String.format("Added %s default dependencies to main package.json", added);
            log().info(message);
        }
        return added > 0;
    }

    int addDependency(JsonObject json, String key, String pkg, String version) {
        Objects.requireNonNull(json, "Json object need to be given");
        Objects.requireNonNull(key, "Json sub object needs to be give.");
        Objects.requireNonNull(pkg, "dependency package needs to be defined");

        JsonObject vaadinDeps = json.getObject(VAADIN_DEP_KEY);
        if (!json.hasKey(key)) {
            json.put(key, Json.createObject());
        }
        json = json.get(key);
        vaadinDeps = vaadinDeps.getObject(key);

        if (vaadinDeps.hasKey(pkg)) {
            if (version == null) {
                version = vaadinDeps.getString(pkg);
            }
            return handleExistingVaadinDep(json, pkg, version, vaadinDeps);
        } else {
            vaadinDeps.put(pkg, version);
            if (!json.hasKey(pkg) || isNewerVersion(json, pkg, version)) {
                json.put(pkg, version);
                log().debug("Added \"{}\": \"{}\" line.", pkg, version);
                return 1;
            }
        }
        return 0;
    }

    private boolean isNewerVersion(JsonObject json, String pkg,
                                   String version) {
        try {
            FrontendVersion newVersion = new FrontendVersion(version);
            FrontendVersion existingVersion = toVersion(json, pkg);
            return newVersion.isNewerThan(existingVersion);
        } catch (NumberFormatException e) {
            if (VAADIN_FORM_PKG.equals(pkg) && json.getString(pkg)
                    .contains(VAADIN_FORM_PKG_LEGACY_VERSION)) {
                return true;
            } else {
                // NPM package versions are not always easy to parse, see
                // https://docs.npmjs.com/cli/v8/configuring-npm/package-json#dependencies
                // for some examples. So let's return false for unparsable
                // versions, as we don't want them to be updated.
                String message = String.format("Package %s has unparseable version: %s", pkg, e.getMessage());
                log().warn(message);
                return false;
            }
        }
    }

    private int handleExistingVaadinDep(JsonObject json, String pkg,
                                        String version, JsonObject vaadinDeps) {
        boolean added = false;
        boolean updatedVaadinVersionSection = false;
        try {
            FrontendVersion vaadinVersion = toVersion(vaadinDeps, pkg);
            if (json.hasKey(pkg)) {
                FrontendVersion packageVersion = toVersion(json, pkg);
                FrontendVersion newVersion = new FrontendVersion(version);
                // Vaadin and package.json versions are the same, but dependency
                // updates (can be up or down)
                if (vaadinVersion.isEqualTo(packageVersion)
                        && !vaadinVersion.isEqualTo(newVersion)) {
                    json.put(pkg, version);
                    added = true;
                    // if vaadin and package not the same, but new version is
                    // newer
                    // update package version.
                } else if (newVersion.isNewerThan(packageVersion)) {
                    json.put(pkg, version);
                    added = true;
                }
            } else {
                json.put(pkg, version);
                added = true;
            }
        } catch (NumberFormatException e) { // NOSONAR
            /*
             * If the current version is not parseable, it can refer to a file
             * and we should leave it alone
             */
        }
        // always update vaadin version to the latest set version
        if (!version.equals(vaadinDeps.getString(pkg))) {
            vaadinDeps.put(pkg, version);
            updatedVaadinVersionSection = true;
        }

        if (added) {
            log().debug("Added \"{}\": \"{}\" line.", pkg, version);
        } else {
            // we made a change to the package json vaadin defaults
            // even if we didn't add to the dependencies.
            added = updatedVaadinVersionSection;
        }
        return added ? 1 : 0;
    }

    private static FrontendVersion toVersion(JsonObject json, String key) {
        return new FrontendVersion(json.getString(key));
    }

    String writePackageFile(JsonObject packageJson) throws IOException {
        return writePackageFile(packageJson, getStudioJsonFile());
    }


    String writePackageFile(JsonObject json, File packageFile)
            throws IOException {
        String content = stringify(json, 2) + "\n";
        if (!packageFile.exists() ) {
            packageFile.createNewFile();
        }
        if (packageFile.exists() || options.isFrontendHotdeploy()
                || options.isBundleBuild()) {
            String message = String.format("writing file %s.", packageFile.getAbsolutePath());
            log().debug(message);
            FileUtils.forceMkdirParent(packageFile);
            FileIOUtils.writeIfChanged(packageFile, content);
        }
        return content;
    }

    File getVaadinJsonFile() {
        return new File(new File(options.getStudioFolder(), NODE_MODULES),
                VAADIN_JSON);
    }

    JsonObject getVaadinJsonContents() throws IOException {
        File vaadinJsonFile = getVaadinJsonFile();
        if (vaadinJsonFile.exists()) {
            String fileContent = FileUtils.readFileToString(vaadinJsonFile,
                    UTF_8.name());
            return Json.parse(fileContent);
        } else {
            return Json.createObject();
        }
    }

    void updateVaadinJsonContents(Map<String, String> newContent)
            throws IOException {
        JsonObject fileContent = getVaadinJsonContents();
        newContent.forEach(fileContent::put);
        File vaadinJsonFile = getVaadinJsonFile();
        FileUtils.forceMkdirParent(vaadinJsonFile);
        String content = stringify(fileContent, 2) + "\n";
        FileIOUtils.writeIfChanged(vaadinJsonFile, content);
    }

    Logger log() {
        return LoggerFactory.getLogger(this.getClass());
    }

    /**
     * Generate versions json file for version locking.
     *
     * @param packageJson
     *            the package json content
     * @throws IOException
     *             when file IO fails
     */
    protected void generateVersionsJson(JsonObject packageJson)
            throws IOException {
        versionsJson = getPlatformPinnedDependencies();
        JsonObject packageJsonVersions = generateVersionsFromPackageJson(
                packageJson);
        if (versionsJson.keys().length == 0) {
            versionsJson = packageJsonVersions;
        } else {
            for (String key : packageJsonVersions.keys()) {
                if (!versionsJson.hasKey(key)) {
                    versionsJson.put(key, packageJsonVersions.getString(key));
                }
            }
        }
    }

    /**
     * If we do not have the platform versions to lock we should lock any
     * versions in the package.json so we do not get multiple versions for
     * defined packages.
     *
     * @return versions Json based on package.json
     * @throws IOException
     *             If reading package.json fails
     */
    private JsonObject generateVersionsFromPackageJson(JsonObject packageJson)
            throws IOException {
        JsonObject versionsJson = Json.createObject();
        // if we don't have versionsJson lock package dependency versions.
        final JsonObject dependencies = packageJson.getObject(DEPENDENCIES);
        if (dependencies != null) {
            for (String key : dependencies.keys()) {
                versionsJson.put(key, dependencies.getString(key));
            }
        }

        return versionsJson;
    }
    /**
     * Adds Hilla components to package.json if Hilla is used in the project.
     *
     * @param dependencies
     *            to be added into package.json
     * @param packageJsonKey
     *            the key inside package.json containing the sub-list of
     *            dependencies to read and add
     * @see <a href=
     *      "https://github.com/vaadin/hilla/tree/main/packages/java/hilla/src/main/resources/com/vaadin/flow/server/frontend/dependencies/hilla/components</a>
     */
    private void putHillaComponentsDependencies(
            Map<String, String> dependencies, String packageJsonKey) {
        if (FrontendUtils.isHillaUsed(options.getFrontendDirectory(),
                options.getClassFinder())) {
            if (options.isReactEnabled()) {
                dependencies.putAll(readDependenciesIfAvailable(
                        "hilla/components/react", packageJsonKey));
            } else {
                dependencies.putAll(readDependenciesIfAvailable(
                        "hilla/components/lit", packageJsonKey));
            }
        }
    }
}
