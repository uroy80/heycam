# The app's R8 rules for the device-test build only (-Pheycam.testBuildType=release, PLAN D23), on top of
# proguard-rules.pro. The tests run against the app as it ships: heycam's code, Kotlin, kotlinx.coroutines and
# kotlinx.serialization stay shrunk, optimised and obfuscated. The test APK is built against this build's mapping, so
# names may change; these rules only stop R8 from removing or rewriting what the test APK calls.
#
# Keep it to that. After changing a device test, run
#   ./gradlew -Pheycam.testBuildType=release :app:assembleRelease :app:assembleReleaseAndroidTest
#   python3 -I scripts/check-test-apk.py
# which prints anything the tests need that R8 removed, as rules to add below.

# Hilt generates the tests' own components inside the test APK. They call the app's generated factories, module
# providers, member injectors and ViewModel keys, implement its injector interfaces, and run on the Dagger runtime.
-keep,allowobfuscation class heycam.**_*Factory { *; }
-keep,allowobfuscation class heycam.**_Factory_Impl { *; }
-keep,allowobfuscation class heycam.**_MembersInjector { *; }
-keep,allowobfuscation interface heycam.**_GeneratedInjector { *; }
-keep,allowobfuscation class heycam.**_HiltModules* { *; }
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }

# FakeScannerLauncher (:core:testing) replaces the ML Kit scanner in every Hilt device test.
-keep,allowobfuscation interface heycam.core.scanner.ScannerLauncher { <methods>; }

# What the tests call in heycam: EndToEndTest drives the data, domain and export layers; AppManifestTest reads the
# licence list; EntryPointsTest builds the tile intent; FakeScannerLauncher stages its sample pages.
-keep,allowobfuscation class heycam.app.entry.ScanTileService {
    heycam.app.entry.ScanTileService$Companion Companion;
}
-keep,allowobfuscation class heycam.app.entry.ScanTileService$Companion {
    android.content.Intent scanIntent(android.content.Context);
}
-keep,allowobfuscation class heycam.core.common.StagingArea {
    java.io.File newStagedFile(java.lang.String);
}
-keep,allowobfuscation class heycam.core.data.DocumentRepository {
    java.lang.Object deleteForever(java.util.List,kotlin.coroutines.Continuation);
    java.lang.Object restore(java.util.List,kotlin.coroutines.Continuation);
    java.lang.Object search(heycam.core.model.SearchQuery,kotlin.coroutines.Continuation);
    java.lang.Object trash(java.util.List,kotlin.coroutines.Continuation);
    kotlinx.coroutines.flow.Flow observeDocument-1S7AcUM(long);
    kotlinx.coroutines.flow.Flow observeTrash();
}
-keep,allowobfuscation class heycam.core.data.FileStore {
    java.io.File exportDir();
}
-keep,allowobfuscation class heycam.core.data.ScanSessionStore {
    java.lang.Object create(heycam.core.model.ImportTarget,java.util.List,kotlin.coroutines.Continuation);
    java.lang.Object get(java.lang.String,kotlin.coroutines.Continuation);
    java.lang.Object update(heycam.core.model.ScanSession,kotlin.coroutines.Continuation);
}
-keep,allowobfuscation class heycam.core.data.SettingsRepository {
    java.lang.Object update(kotlin.jvm.functions.Function1,kotlin.coroutines.Continuation);
    kotlinx.coroutines.flow.Flow getSettings();
}
-keep,allowobfuscation class heycam.core.domain.CommitResult {
    long getDocumentId-_C2Ljos();
}
-keep,allowobfuscation class heycam.core.domain.PageProcessor {
    java.lang.Object awaitIdle(java.util.Collection,kotlin.coroutines.Continuation);
}
-keep,allowobfuscation class heycam.core.domain.ScanImporter {
    java.lang.Object commit(heycam.core.model.ScanSession,kotlin.coroutines.Continuation);
    java.lang.Object stage(java.util.List,heycam.core.model.PageOrigin,kotlin.coroutines.Continuation);
}
-keep,allowobfuscation class heycam.core.domain.StageResult {
    int getRejected();
    java.util.List getImages();
}
-keep,allowobfuscation class heycam.core.domain.TextModels {
    kotlinx.coroutines.flow.Flow download(java.util.Set);
}
-keep,allowobfuscation class heycam.core.export.ExportResult$ReadyToShare {
    android.content.Intent getChooser();
}
-keep,allowobfuscation class heycam.core.export.ExportService {
    java.lang.Object export$default(heycam.core.export.ExportService,heycam.core.export.ExportRequest,heycam.core.export.ExportTarget,kotlin.jvm.functions.Function1,kotlin.coroutines.Continuation,int,java.lang.Object);
}
-keep,allowobfuscation class heycam.core.model.Document {
    java.util.List getPages();
}
-keep,allowobfuscation class heycam.core.model.DocumentId {
    heycam.core.model.DocumentId box-impl(long);
    long unbox-impl();
}
-keep,allowobfuscation class heycam.core.model.DocumentSummary {
    long getId-_C2Ljos();
}
-keep,allowobfuscation class heycam.core.model.ExportOptions {
    heycam.core.model.ExportFormat getFormat();
    void <init>(heycam.core.model.ExportFormat,heycam.core.model.SizePreset,heycam.core.model.PageSize,heycam.core.model.Margin,boolean,java.util.List,java.lang.String,heycam.core.model.Password,boolean,java.lang.String,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep,allowobfuscation class heycam.core.model.ImportTarget$NewDocument {
    void <init>(java.lang.String,heycam.core.model.FolderId,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep,allowobfuscation class heycam.core.model.Page {
    heycam.core.model.ImageInfo getRender();
    heycam.core.model.PageEdits getEdits();
    heycam.core.model.TextStatus getTextStatus();
}
-keep,allowobfuscation class heycam.core.model.PageEdits {
    heycam.core.model.PageEdits copy$default(heycam.core.model.PageEdits,heycam.core.model.Rotation,heycam.core.model.PageFilter,heycam.core.model.Quad,heycam.core.model.Adjustments,int,java.lang.Object);
    heycam.core.model.PageFilter getFilter();
}
-keep,allowobfuscation class heycam.core.model.ScanSession {
    heycam.core.model.ScanSession copy$default(heycam.core.model.ScanSession,java.lang.String,heycam.core.model.ImportTarget,java.util.List,java.time.Instant,int,java.lang.Object);
    java.lang.String getId();
    java.util.List getPages();
}
-keep,allowobfuscation class heycam.core.model.SearchHit$TextHit {
    heycam.core.model.DocumentSummary getDocument();
}
-keep,allowobfuscation class heycam.core.model.SearchQuery {
    void <init>(java.lang.String,heycam.core.model.FolderId,boolean,int,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep,allowobfuscation class heycam.core.model.Settings {
    heycam.core.model.Settings copy$default(heycam.core.model.Settings,heycam.core.model.ThemeSetting,heycam.core.model.OcrScript,boolean,heycam.core.model.PageFilter,heycam.core.model.ExportOptions,int,heycam.core.model.AppLockSettings,boolean,boolean,int,java.lang.Object);
}
-keep,allowobfuscation class heycam.core.model.StagedPage {
    heycam.core.model.PageEdits getEdits();
    heycam.core.model.StagedPage copy$default(heycam.core.model.StagedPage,heycam.core.model.StagedImage,heycam.core.model.PageEdits,int,java.lang.Object);
}
-keep,allowobfuscation class heycam.core.scanner.ScanRequest {
    java.lang.Integer getPageLimit();
}
-keep,allowobfuscation class heycam.core.scanner.ScanStart$Immediate {
    void <init>(heycam.core.scanner.ScanResult);
}
-keep,allowobfuscation class heycam.feature.settings.LicenceText$Asset {
    java.lang.String getPath();
}
-keep,allowobfuscation class heycam.feature.settings.OpenSourceComponent {
    heycam.feature.settings.LicenceText getText();
}
-keep,allowobfuscation class heycam.feature.settings.OpenSourceLicences {
    heycam.feature.settings.OpenSourceLicences INSTANCE;
    java.util.List getComponents();
}

# AndroidX classes the app ships and the test libraries call: the runner traces, ActivityScenario waits on futures,
# and EndToEndTest reads share intents.
-keep,allowobfuscation class androidx.concurrent.futures.CallbackToFutureAdapter {
    com.google.common.util.concurrent.ListenableFuture getFuture(androidx.concurrent.futures.CallbackToFutureAdapter$Resolver);
}
-keep,allowobfuscation class androidx.concurrent.futures.CallbackToFutureAdapter$Completer {
    boolean set(java.lang.Object);
    boolean setException(java.lang.Throwable);
}
-keep,allowobfuscation class androidx.concurrent.futures.CallbackToFutureAdapter$Resolver
-keep,allowobfuscation class androidx.concurrent.futures.ResolvableFuture {
    androidx.concurrent.futures.ResolvableFuture create();
    boolean set(java.lang.Object);
    boolean setException(java.lang.Throwable);
    void addListener(java.lang.Runnable,java.util.concurrent.Executor);
}
-keep,allowobfuscation class androidx.core.content.IntentCompat {
    java.lang.Object getParcelableExtra(android.content.Intent,java.lang.String,java.lang.Class);
    java.util.ArrayList getParcelableArrayListExtra(android.content.Intent,java.lang.String,java.lang.Class);
}
# The test APK has its own Guava; CallbackToFutureAdapter must hand back the interface by its own name.
-keep interface com.google.common.util.concurrent.ListenableFuture
-keep,allowobfuscation class androidx.tracing.Trace {
    void beginSection(java.lang.String);
    void endSection();
    void forceEnableAppTracing();
}

# Kotlin and kotlinx.coroutines functions the test code calls (Kotlin compiles some of them into every suspend
# function). Multifile facades such as CollectionsKt inherit them from their parts, which R8 names separately.
-keep,allowobfuscation class kotlin.LazyKt
-keep,allowobfuscation class kotlin.LazyKt__LazyJVMKt {
    kotlin.Lazy lazy(kotlin.jvm.functions.Function0);
}
-keep,allowobfuscation class kotlin.Result {
    java.lang.Object constructor-impl(java.lang.Object);
    kotlin.Result$Companion Companion;
}
-keep,allowobfuscation class kotlin.ResultKt {
    java.lang.Object createFailure(java.lang.Throwable);
    void throwOnFailure(java.lang.Object);
}
-keep,allowobfuscation class kotlin.TuplesKt {
    kotlin.Pair to(java.lang.Object,java.lang.Object);
}
-keep,allowobfuscation class kotlin.collections.AbstractIterator {
    void <init>();
    void done();
    void setNext(java.lang.Object);
}
-keep,allowobfuscation class kotlin.collections.ArraysKt {
    java.lang.Object first(java.lang.Object[]);
}
-keep,allowobfuscation class kotlin.collections.ArraysKt___ArraysJvmKt {
    byte[] copyOfRange(byte[],int,int);
}
-keep,allowobfuscation class kotlin.collections.ArraysKt___ArraysKt {
    java.lang.Object first(java.lang.Object[]);
    java.util.Set toSet(java.lang.Object[]);
}
-keep,allowobfuscation class kotlin.collections.CollectionsKt
-keep,allowobfuscation class kotlin.collections.CollectionsKt__CollectionsJVMKt {
    java.util.List listOf(java.lang.Object);
}
-keep,allowobfuscation class kotlin.collections.CollectionsKt__CollectionsKt {
    java.util.List emptyList();
    java.util.List listOf(java.lang.Object[]);
    java.util.List listOfNotNull(java.lang.Object);
}
-keep,allowobfuscation class kotlin.collections.CollectionsKt__IteratorsJVMKt {
    java.util.Iterator iterator(java.util.Enumeration);
}
-keep,allowobfuscation class kotlin.collections.CollectionsKt__MutableCollectionsKt {
    boolean addAll(java.util.Collection,java.lang.Iterable);
}
-keep,allowobfuscation class kotlin.collections.CollectionsKt___CollectionsKt {
    java.lang.Object single(java.util.List);
    java.lang.String joinToString$default(java.lang.Iterable,java.lang.CharSequence,java.lang.CharSequence,java.lang.CharSequence,int,java.lang.CharSequence,kotlin.jvm.functions.Function1,int,java.lang.Object);
    java.util.List distinct(java.lang.Iterable);
    java.util.List plus(java.util.Collection,java.lang.Object[]);
    java.util.List take(java.lang.Iterable,int);
    java.util.List toList(java.lang.Iterable);
}
-keep,allowobfuscation class kotlin.collections.MapsKt
-keep,allowobfuscation class kotlin.collections.MapsKt__MapWithDefaultKt {
    java.lang.Object getOrImplicitDefaultNullable(java.util.Map,java.lang.Object);
}
-keep,allowobfuscation class kotlin.collections.MapsKt__MapsJVMKt {
    int mapCapacity(int);
}
-keep,allowobfuscation class kotlin.collections.MapsKt__MapsKt {
    java.lang.Object getValue(java.util.Map,java.lang.Object);
    java.util.Map mapOf(kotlin.Pair[]);
}
-keep,allowobfuscation class kotlin.collections.SetsKt
-keep,allowobfuscation class kotlin.collections.SetsKt__SetsJVMKt {
    java.util.Set setOf(java.lang.Object);
}
-keep,allowobfuscation class kotlin.collections.SetsKt__SetsKt {
    java.util.Set setOf(java.lang.Object[]);
}
-keep,allowobfuscation class kotlin.collections.SetsKt___SetsKt {
    java.util.Set minus(java.util.Set,java.lang.Iterable);
}
-keep,allowobfuscation class kotlin.coroutines.ContinuationKt {
    kotlin.coroutines.Continuation createCoroutine(kotlin.jvm.functions.Function1,kotlin.coroutines.Continuation);
}
-keep,allowobfuscation class kotlin.coroutines.intrinsics.IntrinsicsKt
-keep,allowobfuscation class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt {
    kotlin.coroutines.Continuation intercepted(kotlin.coroutines.Continuation);
}
-keep,allowobfuscation class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsKt {
    java.lang.Object getCOROUTINE_SUSPENDED();
}
-keep,allowobfuscation class kotlin.coroutines.jvm.internal.Boxing {
    java.lang.Integer boxInt(int);
}
# Every suspend lambda in the test APK (each runBlocking block) overrides create(Object, Continuation). Left free,
# R8 swaps its parameters, the lambdas override nothing, and runBlocking throws "create(Any?;Continuation) has not
# been overridden" (scripts/check-test-apk.py reports such overrides).
-keep,allowobfuscation class kotlin.coroutines.jvm.internal.BaseContinuationImpl {
    kotlin.coroutines.Continuation create(java.lang.Object,kotlin.coroutines.Continuation);
}
# R8 maps this one in a form the test APK's build cannot apply, so its name stays.
-keep class kotlin.coroutines.jvm.internal.DebugProbesKt {
    void probeCoroutineSuspended(kotlin.coroutines.Continuation);
}
-keep,allowobfuscation class kotlin.coroutines.jvm.internal.SuspendLambda {
    void <init>(int,kotlin.coroutines.Continuation);
}
-keep,allowobfuscation class kotlin.coroutines.jvm.internal.SpillingKt {
    java.lang.Object nullOutSpilledVariable(java.lang.Object);
}
-keep,allowobfuscation class kotlin.coroutines.jvm.internal.SuspendFunction
-keep,allowobfuscation class kotlin.enums.EnumEntriesKt {
    kotlin.enums.EnumEntries enumEntries(java.lang.Enum[]);
}
-keep,allowobfuscation class kotlin.io.ByteStreamsKt {
    long copyTo$default(java.io.InputStream,java.io.OutputStream,int,int,java.lang.Object);
}
-keep,allowobfuscation class kotlin.io.CloseableKt {
    void closeFinally(java.io.Closeable,java.lang.Throwable);
}
-keep,allowobfuscation class kotlin.io.FilesKt
-keep,allowobfuscation class kotlin.io.FilesKt__FileReadWriteKt {
    byte[] readBytes(java.io.File);
    java.lang.String readText$default(java.io.File,java.nio.charset.Charset,int,java.lang.Object);
}
-keep,allowobfuscation class kotlin.io.FilesKt__UtilsKt {
    boolean deleteRecursively(java.io.File);
}
-keep,allowobfuscation class kotlin.io.TextStreamsKt {
    java.lang.String readText(java.io.Reader);
}
-keep,allowobfuscation class kotlin.jdk7.AutoCloseableKt {
    void closeFinally(java.lang.AutoCloseable,java.lang.Throwable);
}
-keep,allowobfuscation class kotlin.jvm.internal.Reflection {
    kotlin.reflect.KClass getOrCreateKotlinClass(java.lang.Class);
}
-keep,allowobfuscation class kotlin.jvm.internal.StringCompanionObject {
    kotlin.jvm.internal.StringCompanionObject INSTANCE;
}
-keep,allowobfuscation class kotlin.ranges.ClosedRange
-keep,allowobfuscation class kotlin.ranges.RangesKt {
    int coerceAtLeast(int,int);
}
-keep,allowobfuscation class kotlin.ranges.RangesKt___RangesKt {
    kotlin.ranges.IntRange until(int,int);
}
-keep,allowobfuscation class kotlin.reflect.KClass {
    java.lang.String getSimpleName();
}
-keep,allowobfuscation class kotlin.sequences.SequencesKt {
    kotlin.sequences.Sequence map(kotlin.sequences.Sequence,kotlin.jvm.functions.Function1);
}
-keep,allowobfuscation class kotlin.sequences.SequencesKt__SequencesKt {
    kotlin.sequences.Sequence asSequence(java.util.Iterator);
}
-keep,allowobfuscation class kotlin.sequences.SequencesKt___SequencesKt {
    java.util.List toList(kotlin.sequences.Sequence);
    kotlin.sequences.Sequence filter(kotlin.sequences.Sequence,kotlin.jvm.functions.Function1);
    kotlin.sequences.Sequence map(kotlin.sequences.Sequence,kotlin.jvm.functions.Function1);
}
-keep,allowobfuscation class kotlin.text.StringsKt
-keep,allowobfuscation class kotlin.text.StringsKt__StringsJVMKt {
    boolean endsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    boolean startsWith$default(java.lang.String,java.lang.String,boolean,int,java.lang.Object);
    java.lang.String decodeToString(byte[]);
}
-keep,allowobfuscation class kotlin.text.StringsKt__StringsKt {
    java.lang.String removePrefix(java.lang.String,java.lang.CharSequence);
    java.lang.String removeSuffix(java.lang.String,java.lang.CharSequence);
    java.lang.String substringAfterLast$default(java.lang.String,char,java.lang.String,int,java.lang.Object);
}
-keep,allowobfuscation class kotlin.time.Duration {
    kotlin.time.Duration$Companion Companion;
}
-keep,allowobfuscation class kotlin.time.DurationKt {
    long toDuration(int,kotlin.time.DurationUnit);
}
-keep,allowobfuscation class kotlinx.coroutines.BuildersKt {
    java.lang.Object runBlocking(kotlin.coroutines.CoroutineContext,kotlin.jvm.functions.Function2);
    java.lang.Object runBlockingK$default(kotlin.coroutines.CoroutineContext,kotlin.jvm.functions.Function2,int,java.lang.Object);
    kotlinx.coroutines.Deferred async(kotlinx.coroutines.CoroutineScope,kotlin.coroutines.CoroutineContext,kotlinx.coroutines.CoroutineStart,kotlin.jvm.functions.Function2);
}
-keep,allowobfuscation class kotlinx.coroutines.BuildersKt__BuildersKt {
    java.lang.Object runBlocking(kotlin.coroutines.CoroutineContext,kotlin.jvm.functions.Function2);
}
-keep,allowobfuscation class kotlinx.coroutines.BuildersKt__Builders_commonKt {
    kotlinx.coroutines.Deferred async(kotlinx.coroutines.CoroutineScope,kotlin.coroutines.CoroutineContext,kotlinx.coroutines.CoroutineStart,kotlin.jvm.functions.Function2);
}
-keep,allowobfuscation class kotlinx.coroutines.BuildersKt__Builders_concurrentKt {
    java.lang.Object runBlockingK$default(kotlin.coroutines.CoroutineContext,kotlin.jvm.functions.Function2,int,java.lang.Object);
}
-keep,allowobfuscation class kotlinx.coroutines.CancellableContinuation {
    void resume(java.lang.Object,kotlin.jvm.functions.Function1);
}
-keep,allowobfuscation class kotlinx.coroutines.CancellableContinuationImpl {
    void <init>(kotlin.coroutines.Continuation,int);
}
-keep,allowobfuscation class kotlinx.coroutines.Deferred {
    java.lang.Object await(kotlin.coroutines.Continuation);
}
-keep,allowobfuscation class kotlinx.coroutines.Dispatchers {
    kotlinx.coroutines.CoroutineDispatcher getUnconfined();
    kotlinx.coroutines.MainCoroutineDispatcher getMain();
}
-keep,allowobfuscation class kotlinx.coroutines.ExecutorsKt {
    kotlinx.coroutines.CoroutineDispatcher from(java.util.concurrent.Executor);
}
-keep,allowobfuscation class kotlinx.coroutines.Job$DefaultImpls {
    void cancel$default(kotlinx.coroutines.Job,java.util.concurrent.CancellationException,int,java.lang.Object);
}
-keep,allowobfuscation class kotlinx.coroutines.TimeoutKt {
    java.lang.Object withTimeout(long,kotlin.jvm.functions.Function2,kotlin.coroutines.Continuation);
    java.lang.Object withTimeout-KLykuaI(long,kotlin.jvm.functions.Function2,kotlin.coroutines.Continuation);
}
-keep,allowobfuscation class kotlinx.coroutines.flow.FlowKt {
    java.lang.Object first(kotlinx.coroutines.flow.Flow,kotlin.coroutines.Continuation);
    java.lang.Object last(kotlinx.coroutines.flow.Flow,kotlin.coroutines.Continuation);
    kotlinx.coroutines.flow.Flow flow(kotlin.jvm.functions.Function2);
}
-keep,allowobfuscation class kotlinx.coroutines.flow.FlowKt__BuildersKt {
    kotlinx.coroutines.flow.Flow flow(kotlin.jvm.functions.Function2);
}
-keep,allowobfuscation class kotlinx.coroutines.flow.FlowKt__ReduceKt {
    java.lang.Object first(kotlinx.coroutines.flow.Flow,kotlin.coroutines.Continuation);
    java.lang.Object last(kotlinx.coroutines.flow.Flow,kotlin.coroutines.Continuation);
}
