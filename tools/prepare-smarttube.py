from pathlib import Path
import re
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "niktube-smarttube"
ANDROID = "http://schemas.android.com/apk/res/android"
TOOLS = "http://schemas.android.com/tools"
ET.register_namespace("android", ANDROID)
ET.register_namespace("tools", TOOLS)
MODULES = {
    "smarttubetv": "smarttubetv", "common": "common", "chatkit": "chatkit",
    "leanbackassistant": "leanbackassistant", "leanback-1.0.0": "leanback-1.0.0",
    "fragment-1.1.0": "fragment-1.1.0", "filepicker-lib": "filepicker-lib",
    "doubletapplayerview": "doubletapplayerview", "slidableactivity": "slidableactivity",
    "sharedutils": "SharedModules/sharedutils", "sharedtests": "SharedModules/sharedtests",
    "appupdatechecker2": "SharedModules/appupdatechecker2", "commons-io-2.8.0": "SharedModules/commons-io-2.8.0",
    "j2v8": "SharedModules/j2v8", "mediaserviceinterfaces": "MediaServiceCore/mediaserviceinterfaces",
    "youtubeapi": "MediaServiceCore/youtubeapi",
}
for name in ("core", "dash", "sabr", "hls", "smoothstreaming", "ui"):
    MODULES["exoplayer-library-" + name] = "exoplayer-amzn-2.10.6/library/" + name
MODULES["exoplayer-library"] = "exoplayer-amzn-2.10.6/library/all"
for name in ("okhttp", "cronet", "mediasession", "leanback", "ffmpeg", "flac", "opus", "vp9"):
    MODULES["exoplayer-extension-" + name] = "exoplayer-amzn-2.10.6/extensions/" + name
for name, folder in (("testutils", "testutils"), ("testutils-robolectric", "testutils_robolectric")):
    MODULES["exoplayer-" + name] = "exoplayer-amzn-2.10.6/" + folder

PLAYER_RES = SOURCE / "exoplayer-amzn-2.10.6/library/ui/src/main/res"
PLAYER_NAMES = {}
STYLEABLES = {}
for path in PLAYER_RES.rglob("*"):
    if not path.is_file():
        continue
    resource_type = path.parent.name.split("-")[0]
    if resource_type != "values":
        PLAYER_NAMES.setdefault(resource_type, set()).add(path.name.split(".")[0])
    if path.suffix != ".xml":
        continue
    xml_text = path.read_text(encoding="utf-8-sig")
    PLAYER_NAMES.setdefault("id", set()).update(re.findall(r"@\+id/([A-Za-z0-9_]+)", xml_text))
    if resource_type == "values":
        root = ET.fromstring(xml_text)
        for item in root:
            item_type = item.get("type", item.tag)
            item_name = item.get("name", "")
            if item_type == "declare-styleable":
                STYLEABLES[item_name] = [attr.get("name", "") for attr in item.findall("attr")]
                item_type = "styleable"
            if item_name:
                PLAYER_NAMES.setdefault(item_type, set()).add(item_name)
        PLAYER_NAMES.setdefault("attr", set()).update(attr.get("name") for attr in root.iter("attr") if attr.get("name") and not attr.get("name").startswith("android:"))

def scope_player_resources(text):
    fields = {}
    for resource_type, names in PLAYER_NAMES.items():
        for name in names:
            field = name.replace(".", "_")
            fields[(resource_type, field)] = "st_" + field
            text = text.replace("@" + resource_type + "/" + name + '"', "@" + resource_type + "/st_" + name + '"')
            text = text.replace("@" + resource_type + "/" + name + "<", "@" + resource_type + "/st_" + name + "<")
            if resource_type == "id":
                text = text.replace("@+id/" + name + '"', "@+id/st_" + name + '"')
            if resource_type == "attr":
                text = text.replace("app:" + name + "=", "app:st_" + name + "=")
    for styleable, attrs in STYLEABLES.items():
        for attr in attrs:
            suffix = attr.replace(":", "_")
            scoped = suffix if attr.startswith("android:") else "st_" + suffix
            fields[("styleable", styleable + "_" + suffix)] = "st_" + styleable + "_" + scoped
    text = re.sub(r"R\.(\w+)\.([A-Za-z0-9_]+)", lambda m: "R." + m[1] + "." + fields.get((m[1], m[2]), m[2]), text)
    return text

def relocate(text):
    return text.replace("androidx.fragment", "com.nikhil.smarttube.fragment")

def replace_body(text, signature, body):
    start = text.index(signature) + len(signature)
    opening = text.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[:opening + 1] + "\n" + body + "\n    " + text[end - 1:]

def dependencies(text):
    match = list(re.finditer(r"(?m)^dependencies\s*\{", text))
    if not match:
        return "dependencies {}"
    start = match[-1].start()
    pos = text.index("{", start)
    depth = 1
    end = pos + 1
    while depth and end < len(text):
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    block = text[start:end]
    block = re.sub(r"(?m)^\s*st(?:beta|stable)Implementation\('com.google.firebase:firebase-crashlytics:.*?\{.*?^\s*\}", "", block, flags=re.S)
    block = re.sub(r"(?m)^\s*st(?:beta|fdroid)Implementation.*$", "", block)
    block = block.replace("ststableImplementation", "implementation")
    block = re.sub(r"(?m)^\s*implementation\('com.google.firebase:firebase-crashlytics:.*?\{.*?^\s*\}", "", block, flags=re.S)
    block = re.sub(r"project\((?:path:\s*)?['\"]:([^'\"]+)['\"]\)", lambda m: "project(':st-" + m[1] + "')", block)
    block = block.replace("project(modulePrefix + '", "project(':st-exoplayer-")
    block = block.replace("fileTree(dir: 'libs'", "fileTree(dir: 'libs'")
    return block

for name, relative in MODULES.items():
    module = SOURCE / relative
    target = module / "build/embedded"
    target.mkdir(parents=True, exist_ok=True)
    generated_main = target / "main"
    if generated_main.exists():
        if not generated_main.resolve().is_relative_to(SOURCE.resolve()) or generated_main.parent.resolve() != target.resolve():
            raise RuntimeError("Unsafe generated source cleanup path")
        shutil.rmtree(generated_main)
    original = module / "src/main"
    if original.exists():
        for path in original.rglob("*"):
            if not path.is_file():
                continue
            destination = target / "main" / path.relative_to(original)
            if name == "exoplayer-library-ui" and "res" in path.relative_to(original).parts and not path.parent.name.startswith("values"):
                destination = destination.with_name("st_" + destination.name)
            destination.parent.mkdir(parents=True, exist_ok=True)
            if path.suffix in (".java", ".kt", ".xml"):
                original_text = path.read_text(encoding="utf-8-sig")
                content = original_text if name == "filepicker-lib" else relocate(original_text)
                content = scope_player_resources(content)
                if name == "leanbackassistant" and path.name == "searchable.xml":
                    content = content.replace('android:searchSuggestAuthority="org.smarttube"', 'android:searchSuggestAuthority="@string/search_authority"')
                    content = content.replace('android:searchSuggestIntentData="content://org.smarttube/video"', 'android:searchSuggestIntentData="@string/niktv_search_intent_data"')
                if name == "exoplayer-library-ui" and path.suffix == ".xml" and path.parent.name.startswith("values"):
                    tree = ET.fromstring(content)
                    for item in tree:
                        value = item.get("name")
                        if value:
                            item.set("name", "st_" + value)
                        parent = item.get("parent")
                        if parent in PLAYER_NAMES.get("style", set()):
                            item.set("parent", "st_" + parent)
                    for attr in tree.iter("attr"):
                        value = attr.get("name")
                        if value and not value.startswith("android:") and not value.startswith("st_"):
                            attr.set("name", "st_" + value)
                    for item in tree.iter("item"):
                        if item.get("name") in PLAYER_NAMES.get("attr", set()):
                            item.set("name", "st_" + item.get("name"))
                    content = ET.tostring(tree, encoding="unicode")
                if path.name == "RobolectricUtil.java":
                    # SDK 37 removed FingerprintManager. Avoid resolving every Shadows
                    # overload just to retrieve a Looper's shadow from old test utilities.
                    content = content.replace("import static org.robolectric.Shadows.shadowOf;",
                                              "import static org.robolectric.shadow.api.Shadow.extract;")
                    content = content.replace("shadowOf(", "extract(")
                if path.name == "MainApplication.java":
                    content = content.replace('System.setProperty("http.keepAlive", "false");', '')
                    marker = "public class MainApplication extends MultiDexApplication {"
                    adapter = """
    private static MainApplication embeddedRuntime;
    public static synchronized void initialize(android.content.Context context) {
        if (embeddedRuntime != null) return;
        MainApplication runtime = new MainApplication();
        runtime.attachBaseContext(context.getApplicationContext());
        embeddedRuntime = runtime;
        runtime.onCreate();
    }
"""
                    content = content.replace(marker, marker + adapter)
                if path.name == "ViewManager.java":
                    content = content.replace("activity.moveTaskToBack(true);", "activity.finish();")
                if path.name == "LeanbackActivity.java":
                    content = content.replace("public void finish() {", '''public void finish() {
        if (getClass().getName().endsWith(".BrowseActivity")) {
            getViewManager().removeTop(this);
            android.content.Intent host = new android.content.Intent().setClassName(getPackageName(), "com.nikhil.niktv.MainActivity");
            host.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(host);
            super.finishReally();
            return;
        }
''')
                    content = content.replace("getViewManager().addOnFinish(sOnFinish);", "")
                if path.name == "AppUpdatePresenter.java":
                    content = replace_body(content, "public void start(boolean forceCheck)", '        if (forceCheck) MessageHelpers.showMessage(getContext(), "Updates are managed in NikTV Settings.");')
                if path.name == "DohProviders.kt":
                    content = content.replace("import okhttp3.HttpUrl", "import okhttp3.HttpUrl\nimport okhttp3.HttpUrl.Companion.toHttpUrlOrNull")
                    content = content.replace("HttpUrl.parse(s)", "s.toHttpUrlOrNull()")
                if path.name == "DriveServiceInt.kt":
                    content = content.replace("import okhttp3.RequestBody", "import okhttp3.RequestBody\nimport okhttp3.RequestBody.Companion.asRequestBody\nimport okhttp3.RequestBody.Companion.toRequestBody\nimport okhttp3.MediaType.Companion.toMediaTypeOrNull")
                    content = content.replace("RequestBody.create(MediaType.parse(FILE_MIME_TYPE), file)", "file.asRequestBody(FILE_MIME_TYPE.toMediaTypeOrNull())")
                    content = content.replace("RequestBody.create(MediaType.parse(FILE_MIME_TYPE), content)", "content.toRequestBody(FILE_MIME_TYPE.toMediaTypeOrNull())")
                if path.suffix == ".kt" and ("/potokennp2/" in path.as_posix() or path.name in ("RetrofitOkHttpHelper.kt", "InfoExtractor.kt", "JavaScriptUtil.kt", "InitialResponseService.kt")):
                    for property_name in ("body", "code", "headers", "url"):
                        content = content.replace("." + property_name + "()", "." + property_name)
                destination.write_text(content, encoding="utf-8")
            else:
                shutil.copy2(path, destination)
    manifest_path = target / "main/AndroidManifest.xml"
    if manifest_path.exists():
        manifest = ET.parse(manifest_path)
        namespace = manifest.getroot().attrib.pop("package", None)
    else:
        namespace = "com.nikhil.smarttube." + re.sub(r"[^a-zA-Z0-9]", "", name)
        manifest = ET.ElementTree(ET.Element("manifest"))
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
    if not namespace:
        namespace = "com.nikhil.smarttube." + re.sub(r"[^a-zA-Z0-9]", "", name)
    application = manifest.getroot().find("application")
    if application is not None:
        application_theme = application.get("{" + ANDROID + "}theme")
        application.attrib.clear()
        for activity in application.findall("activity"):
            if application_theme and "{" + ANDROID + "}theme" not in activity.attrib:
                activity.set("{" + ANDROID + "}theme", application_theme)
        if name == "appupdatechecker2":
            for provider in list(application.findall("provider")):
                application.remove(provider)
    if name == "leanbackassistant":
        for provider in application.findall("provider"):
            provider.set("{" + ANDROID + "}authorities", "${applicationId}.smarttube")
    if name == "smarttubetv":
        for activity in application.findall("activity"):
            if "{" + ANDROID + "}theme" not in activity.attrib:
                activity.set("{" + ANDROID + "}theme", "@style/App.Theme.Leanback.Browse")
            if activity.get("{" + ANDROID + "}launchMode") in ("singleInstance", "singleTask"):
                activity.set("{" + ANDROID + "}launchMode", "singleTop")
            for intent in list(activity.findall("intent-filter")):
                if activity.get("{" + ANDROID + "}name", "").endswith("SplashActivity") or any(category.get("{" + ANDROID + "}name", "").endswith("LAUNCHER") for category in intent.findall("category")):
                    activity.remove(intent)
            if activity.get("{" + ANDROID + "}name", "").endswith("SplashActivity"):
                activity.set("{" + ANDROID + "}launchMode", "singleTop")
        for provider in application.findall("provider"):
            if provider.get("{" + ANDROID + "}name") == "androidx.core.content.FileProvider":
                provider.set("{" + ANDROID + "}name", "com.nikhil.niktv.SmartTubeShareProvider")
    for feature in manifest.getroot().findall("uses-feature"):
        feature.set("{" + ANDROID + "}required", "false")
    manifest.write(manifest_path, encoding="utf-8", xml_declaration=True)
    rules = []
    for pattern in ("*proguard*.pro", "*proguard*.txt", "consumer-rules.pro"):
        for file in module.glob(pattern):
            rules.extend(line for line in file.read_text(encoding="utf-8-sig").splitlines() if not re.match(r"^-(dontobfuscate|dontoptimize|dontshrink|optimizationpasses|optimizations|ignorewarnings|repackageclasses|flattenpackagehierarchy|print)", line.strip()))
    (target / "consumer-rules.pro").write_text("\n".join(rules) + "\n", encoding="utf-8")
    for resource in (target / "main/res").glob("values*/strings.xml"):
        tree = ET.parse(resource)
        changed = False
        for string in tree.getroot().findall("string"):
            if len(re.findall(r"%(?!%)(?!\d+\$)[sdif]", "".join(string.itertext()))) > 1:
                string.set("formatted", "false")
                changed = True
        if changed:
            tree.write(resource, encoding="utf-8", xml_declaration=True)
    build = (module / "build.gradle").read_text(encoding="utf-8-sig")
    settings = """
apply plugin: 'com.android.library'
apply from: rootProject.file('niktube-smarttube/SharedModules/constants.gradle')
android {
    namespace '%s'
    compileSdk 37
    defaultConfig {
        minSdk 24
        buildConfigField 'String', 'FLAVOR', '"ststable"'
        buildConfigField 'String', 'VERSION_NAME', '"32.67"'
        buildConfigField 'int', 'VERSION_CODE', '2457'
        buildConfigField 'long', 'TIMESTAMP', '0L'
        consumerProguardFiles 'build/embedded/consumer-rules.pro'
    }
    buildFeatures { buildConfig true }
    sourceSets.main {
        java.setSrcDirs(['build/embedded/main/java'])
        kotlin.setSrcDirs(['build/embedded/main/java'])
        res.setSrcDirs(['build/embedded/main/res'])
        assets.setSrcDirs(['build/embedded/main/assets'])
        jniLibs.setSrcDirs(['build/embedded/main/jniLibs', 'build/embedded/main/libs'])
        manifest.srcFile 'build/embedded/main/AndroidManifest.xml'
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
        coreLibraryDesugaringEnabled true
    }
}
ext.modulePrefix = ':st-exoplayer-'
ext.protobufVersion = '3.17.3'
ext.androidXTestVersion = '1.6.1'
ext.kotlinVersion = '2.4.10'
ext.kotlinxVersion = '1.11.0'
ext.coreXVersion = '1.19.0'
ext.kotlinCoreXVersion = '1.19.0'
dependencies { coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs_nio:2.1.5' }
configurations.configureEach {
    exclude group: 'androidx.leanback', module: 'leanback'
}
""" % namespace
    if name == "exoplayer-library-sabr":
        settings += """
configurations { sabrProtoc }
dependencies { sabrProtoc 'com.google.protobuf:protoc:3.17.3:windows-x86_64@exe' }
def generateProto = tasks.register('embeddedSabrProto', Exec) {
    def destination = layout.buildDirectory.dir('embedded/proto')
    inputs.files(fileTree('src/main/proto'), configurations.sabrProtoc)
    outputs.dir(destination)
    doFirst {
        destination.get().asFile.mkdirs()
        commandLine([configurations.sabrProtoc.singleFile.absolutePath, '--proto_path=' + file('src/main/proto').absolutePath,
            '--java_out=lite:' + destination.get().asFile.absolutePath] + fileTree('src/main/proto').matching { include '**/*.proto' }.files.collect { it.absolutePath })
    }
}
android.sourceSets.main.java.srcDir layout.buildDirectory.dir('embedded/proto').get().asFile
tasks.named('preBuild').configure { dependsOn generateProto }
"""
    if name == "youtubeapi":
        settings += "\napply from: rootProject.file('tools/upstream-credentials.gradle')\n"
    deps = dependencies(build).replace("'org.jetbrains.kotlinx:kotlinx-coroutines-android:' + kotlinVersion", "'org.jetbrains.kotlinx:kotlinx-coroutines-android:' + kotlinxVersion")
    deps = re.sub(r"('androidx\.test\.ext:junit:')\s*\+\s*(androidXTestVersion|testXSupportVersion)", "'androidx.test.ext:junit:1.2.1'", deps)
    deps = re.sub(r"('androidx\.test\.ext:truth:')\s*\+\s*(androidXTestVersion|testXSupportVersion)", "'androidx.test.ext:truth:1.6.0'", deps)
    deps = re.sub(r"('com\.linkedin\.dexmaker:(?:dexmaker|dexmaker-mockito):')\s*\+\s*dexmakerVersion",
                  lambda match: match.group(1)[:-1] + "2.28.3'", deps)
    (module / "embedded.gradle").write_text(settings + "\n" + deps, encoding="utf-8")

settings_file = SOURCE / "embedded-settings.gradle"
host_assets = SOURCE / "smarttubetv/build/embedded/main/assets/licenses/smarttube"
host_assets.mkdir(parents=True, exist_ok=True)
for filename in ("LICENSE", "upstream-provenance.json"):
    shutil.copyfile(SOURCE / filename, host_assets / filename)
# Keep the upstream themes, but identify the embedded experience as NikTV.
host_res = SOURCE / "smarttubetv/build/embedded/main/res"
logo = ROOT / "app/src/main/res/drawable/niktv_logo_foreground.png"
for resource in host_res.glob("mipmap*/app_logo*.png"):
    shutil.copyfile(logo, resource)
settings_file.write_text("\n".join('include(":st-%s")\nproject(":st-%s").projectDir = new File(settingsDir, "niktube-smarttube/%s")\nproject(":st-%s").buildFileName = "embedded.gradle"' % (name, name, relative, name) for name, relative in MODULES.items()), encoding="utf-8")
print("Prepared", len(MODULES), "SmartTube modules")

