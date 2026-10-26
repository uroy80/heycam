#!/usr/bin/env python3
"""Checks that the R8 release app still has what its device tests call (PLAN D23).

With -Pheycam.testBuildType=release the device tests run against the minified app. The test APK is built against
the app's mapping, so names line up, but nothing at build time checks that R8 kept the app classes and members the
tests use. A missing one only shows up on the device, as NoClassDefFoundError or NoSuchMethodError.

This reads the dex of both APKs and the platform's android.jar. Starting from the test classes, the Hilt test
components, the AndroidX Test and JUnit runners and any ServiceLoader entry something can load, it follows the code
of the test APK to every class it can reach and resolves each class, method and field that code uses. It reports
what the app had before R8 (mapping.txt, usage.txt) but no longer has, app types the tests implement that R8 was
free to call directly (not in seeds.txt), and app methods a test class overrides whose parameters R8 reordered,
each as a keep rule for app/r8-test-entrypoints.pro.

Gradle runs it before every R8 device run (:app:checkReleaseTestApk, a dependency of connectedReleaseAndroidTest).
By hand:
  ./gradlew -Pheycam.testBuildType=release :app:assembleRelease :app:assembleReleaseAndroidTest
  python3 -I scripts/check-test-apk.py

Exit status: 0 when nothing is missing, 1 with the rules to add, 2 when an input is missing.
"""

import argparse
import glob
import os
import struct
import sys
import zipfile

OUT = "app/build/outputs"
DEFAULT_APP = f"{OUT}/apk/release/app-release.apk"
DEFAULT_TEST = f"{OUT}/apk/androidTest/release/app-release-androidTest.apk"
DEFAULT_MAPPING = f"{OUT}/mapping/release"
# Test APK classes that are entered by reflection or by the instrumentation itself rather than from other code.
ROOT_PACKAGES = ("Lheycam/app/", "Ldagger/hilt/android/internal/testing/", "Ldagger/hilt/android/testing/",
                 "Landroidx/test/", "Lorg/junit/", "Ljunit/")
# The runtime resolves these by the call site's own signature (JLS 15.12.4.4), not by a declared method.
SIGNATURE_POLYMORPHIC = ("Ljava/lang/invoke/MethodHandle;", "Ljava/lang/invoke/VarHandle;")
NO_INDEX = 0xFFFFFFFF

# Dalvik instruction width in 16-bit code units, by opcode (formats from the Dalvik bytecode reference).
WIDTH = [1] * 256
for first, last, width in (
        (0x02, 0x02, 2), (0x03, 0x03, 3), (0x05, 0x05, 2), (0x06, 0x06, 3), (0x08, 0x08, 2), (0x09, 0x09, 3),
        (0x13, 0x13, 2), (0x14, 0x14, 3), (0x15, 0x16, 2), (0x17, 0x17, 3), (0x18, 0x18, 5), (0x19, 0x1A, 2),
        (0x1B, 0x1B, 3), (0x1C, 0x1C, 2), (0x1F, 0x20, 2), (0x22, 0x23, 2), (0x24, 0x26, 3), (0x29, 0x29, 2),
        (0x2A, 0x2C, 3), (0x2D, 0x3D, 2), (0x44, 0x6D, 2), (0x6E, 0x78, 3), (0x90, 0xAF, 2), (0xD0, 0xE2, 2),
        (0xFA, 0xFB, 4), (0xFC, 0xFD, 3), (0xFE, 0xFF, 2)):
    for op in range(first, last + 1):
        WIDTH[op] = width
# Which pool the 16-bit index in code unit 1 points into.
TYPE_OPS = {0x1C, 0x1F, 0x20, 0x22, 0x23, 0x24, 0x25}
FIELD_OPS = set(range(0x52, 0x6E))
METHOD_OPS = set(range(0x6E, 0x73)) | set(range(0x74, 0x79)) | {0xFA, 0xFB}


def mutf8(raw):
    """Dex and class files store names as modified UTF-8; decoded the same way on both sides, they compare equal."""
    return raw.replace(b"\xc0\x80", b"\x00").decode("utf-8", "surrogatepass")


def uleb128(data, pos):
    result = shift = 0
    while True:
        byte = data[pos]
        pos += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, pos
        shift += 7


class ClassInfo:
    __slots__ = ("superclass", "interfaces", "methods", "fields", "uses")

    def __init__(self, superclass, interfaces, methods, fields, uses=()):
        self.superclass = superclass
        self.interfaces = interfaces
        self.methods = methods  # "name(params)return"
        self.fields = fields  # "name:type"
        self.uses = uses  # what its code refers to: ("type", desc) or ("method"|"field", holder, member)


class Dex:
    """Types, member references and class definitions of one dex file, with what each class's code refers to."""

    def __init__(self, data):
        def u32(off):
            return struct.unpack_from("<I", data, off)[0]

        def u16(off):
            return struct.unpack_from("<H", data, off)[0]

        def string(idx):
            pos = uleb128(data, u32(string_ids + 4 * idx))[1]
            return mutf8(data[pos:data.index(0, pos)])

        string_ids = u32(0x3C)
        types = [string(u32(u32(0x44) + 4 * i)) for i in range(u32(0x40))]
        protos = []
        for i in range(u32(0x48)):
            base = u32(0x4C) + 12 * i
            params_off = u32(base + 8)
            params = ""
            if params_off:
                params = "".join(types[u16(params_off + 4 + 2 * j)] for j in range(u32(params_off)))
            protos.append(f"({params}){types[u32(base + 4)]}")
        field_refs = []
        for i in range(u32(0x50)):
            base = u32(0x54) + 8 * i
            field_refs.append((types[u16(base)], f"{string(u32(base + 4))}:{types[u16(base + 2)]}"))
        method_refs = []
        for i in range(u32(0x58)):
            base = u32(0x5C) + 8 * i
            method_refs.append((types[u16(base)], string(u32(base + 4)) + protos[u16(base + 2)]))

        def code_uses(code_off, uses):
            size = u32(code_off + 12)
            insns = code_off + 16
            pc = 0
            while pc < size:
                unit = u16(insns + 2 * pc)
                op = unit & 0xFF
                if op == 0x00 and unit in (0x0100, 0x0200, 0x0300):  # switch and array-data payloads
                    if unit == 0x0100:
                        pc += 4 + 2 * u16(insns + 2 * pc + 2)
                    elif unit == 0x0200:
                        pc += 2 + 4 * u16(insns + 2 * pc + 2)
                    else:
                        pc += 4 + (u16(insns + 2 * pc + 2) * u32(insns + 2 * pc + 4) + 1) // 2
                    continue
                if op in TYPE_OPS or op in FIELD_OPS or op in METHOD_OPS:
                    index = u16(insns + 2 * pc + 2)
                    if op in TYPE_OPS:
                        uses.add(("type", types[index]))
                    elif op in FIELD_OPS:
                        uses.add(("field",) + field_refs[index])
                    else:
                        uses.add(("method",) + method_refs[index])
                pc += WIDTH[op]

        self.classes = {}
        for i in range(u32(0x60)):
            base = u32(0x64) + 32 * i
            superclass = None if u32(base + 8) == NO_INDEX else types[u32(base + 8)]
            interfaces = []
            if u32(base + 12):
                list_off = u32(base + 12)
                interfaces = [types[u16(list_off + 4 + 2 * j)] for j in range(u32(list_off))]
            methods, fields, uses = set(), set(), set()
            if u32(base + 24):
                pos = u32(base + 24)
                counts = []
                for _ in range(4):
                    count, pos = uleb128(data, pos)
                    counts.append(count)
                for kind, count in enumerate(counts):
                    idx = 0
                    for _ in range(count):
                        diff, pos = uleb128(data, pos)
                        idx += diff
                        _, pos = uleb128(data, pos)  # access flags
                        if kind < 2:
                            fields.add(field_refs[idx][1])
                            uses.add(("type", field_refs[idx][1].split(":", 1)[1]))
                        else:
                            code_off, pos = uleb128(data, pos)
                            methods.add(method_refs[idx][1])
                            if code_off:
                                code_uses(code_off, uses)
            self.classes[types[u32(base)]] = ClassInfo(superclass, interfaces, methods, fields, uses)


def read_apk(path):
    """Every class an APK defines (all classes*.dex), and its META-INF/services entries as {service: classes}."""
    classes, services = {}, {}
    with zipfile.ZipFile(path) as apk:
        for name in apk.namelist():
            if name.startswith("classes") and name.endswith(".dex"):
                classes.update(Dex(apk.read(name)).classes)
            elif name.startswith("META-INF/services/") and not name.endswith("/"):
                service = descriptor_of(name.rsplit("/", 1)[1])
                for line in apk.read(name).decode("utf-8").splitlines():
                    line = line.split("#", 1)[0].strip()
                    if line:
                        services.setdefault(service, set()).add(descriptor_of(line))
    return classes, services


def calls_service_loader(classes):
    return any(use[0] == "method" and use[1] == "Ljava/util/ServiceLoader;" and use[2].startswith("load")
               for info in classes.values() for use in info.uses)


class Platform:
    """Classes of android.jar and the optional platform libraries, parsed only when a lookup needs them."""

    def __init__(self, jars):
        self.entries = {}
        for jar in jars:
            archive = zipfile.ZipFile(jar)
            for name in archive.namelist():
                if name.endswith(".class"):
                    self.entries.setdefault(f"L{name[:-6]};", (archive, name))
        self.parsed = {}

    def get(self, descriptor):
        if descriptor not in self.parsed:
            entry = self.entries.get(descriptor)
            self.parsed[descriptor] = parse_class_file(entry[0].read(entry[1])) if entry else None
        return self.parsed[descriptor]


def parse_class_file(data):
    def u16(off):
        return struct.unpack_from(">H", data, off)[0]

    def u32(off):
        return struct.unpack_from(">I", data, off)[0]

    utf8, class_names = {}, {}
    pos, i, count = 10, 1, u16(8)
    sizes = {3: 4, 4: 4, 5: 8, 6: 8, 7: 2, 8: 2, 9: 4, 10: 4, 11: 4, 12: 4, 15: 3, 16: 2, 17: 4, 18: 4, 19: 2, 20: 2}
    while i < count:
        tag = data[pos]
        if tag == 1:
            length = u16(pos + 1)
            utf8[i] = mutf8(data[pos + 3:pos + 3 + length])
            pos += 3 + length
        else:
            if tag == 7:
                class_names[i] = u16(pos + 1)
            pos += 1 + sizes[tag]
            if tag in (5, 6):
                i += 1  # long and double take two constant-pool slots
        i += 1

    def class_at(idx):
        if idx == 0:
            return None
        name = utf8[class_names[idx]]
        return name if name.startswith("[") else f"L{name};"

    superclass = class_at(u16(pos + 4))
    n_interfaces = u16(pos + 6)
    interfaces = [class_at(u16(pos + 8 + 2 * k)) for k in range(n_interfaces)]
    pos += 8 + 2 * n_interfaces
    members = []
    for _ in range(2):  # fields, then methods
        found = set()
        n = u16(pos)
        pos += 2
        for _ in range(n):
            found.add((utf8[u16(pos + 2)], utf8[u16(pos + 4)]))
            n_attributes = u16(pos + 6)
            pos += 8
            for _ in range(n_attributes):
                pos += 6 + u32(pos + 2)
        members.append(found)
    return ClassInfo(superclass, interfaces, {name + desc for name, desc in members[1]},
                     {f"{name}:{desc}" for name, desc in members[0]})


class Mapping:
    """What R8 was given and what it did with it, from the app's mapping.txt and usage.txt.

    before_r8: original names of every class R8 read (mapping.txt lists kept and removed ones, usage.txt removed ones).
    classes: obfuscated -> original class name. renamed: (obfuscated member name, parameters) -> [(original class,
    original name)]. declared: (original member name, parameters) -> the original classes that declare it. Parameters
    are Java type names joined by commas, or None for a field.
    """

    def __init__(self, mapping_dir):
        self.before_r8, self.classes, self.renamed, self.declared = set(), {}, {}, {}
        current = None
        with open(os.path.join(mapping_dir, "mapping.txt"), encoding="utf-8") as mapping:
            for line in mapping:
                if line.lstrip().startswith("#") or not line.strip():
                    continue
                if not line[0].isspace():
                    current, obfuscated = line.rstrip().rstrip(":").split(" -> ")
                    self.before_r8.add(current)
                    self.classes[obfuscated] = current
                elif current:
                    signature, obfuscated = line.strip().rsplit(" -> ", 1)
                    owner, name, params = self._member(signature)
                    self.renamed.setdefault((obfuscated, params), []).append((owner or current, name))
                    self.declared.setdefault((name, params), set()).add(owner or current)
        with open(os.path.join(mapping_dir, "usage.txt"), encoding="utf-8") as usage:
            for line in usage:
                if not line.strip():
                    continue
                if not line[0].isspace():
                    current = line.rstrip().rstrip(":")
                    self.before_r8.add(current)
                else:
                    _, name, params = self._member(line.strip())
                    self.declared.setdefault((name, params), set()).add(current)

    @staticmethod
    def _member(signature):
        """(owner or "", name, params or None) from "[a:b:][modifiers] type [owner.]name[(params)][:c:d]"."""
        head, paren, rest = signature.partition("(")
        qualified = head.split()[-1].split(":")[-1]
        owner, _, name = qualified.rpartition(".")
        return owner, name, rest.split(")", 1)[0] if paren else None


PRIMITIVES = {"V": "void", "Z": "boolean", "B": "byte", "S": "short", "C": "char", "I": "int", "J": "long",
              "F": "float", "D": "double"}


def descriptor_of(name):
    return f"L{name.replace('.', '/')};"


def java_name(descriptor):
    return descriptor[1:-1].replace("/", ".") if descriptor.startswith("L") else descriptor


def java_types(descriptors, rename):
    """"I[Ljava/lang/String;" -> ["int", "java.lang.String[]"], with obfuscated class names put back."""
    types, pos = [], 0
    while pos < len(descriptors):
        dims = 0
        while descriptors[pos] == "[":
            dims, pos = dims + 1, pos + 1
        if descriptors[pos] == "L":
            end = descriptors.index(";", pos)
            name = rename(descriptors[pos + 1:end].replace("/", "."))
            pos = end + 1
        else:
            name, pos = PRIMITIVES[descriptors[pos]], pos + 1
        types.append(name + "[]" * dims)
    return types


def default_platform_jars():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    platforms = sorted(glob.glob(os.path.join(sdk, "platforms", "android-*")))
    if not platforms:
        return ["$ANDROID_HOME/platforms/android-*/android.jar"]
    return [os.path.join(platforms[-1], "android.jar")] + sorted(
        glob.glob(os.path.join(platforms[-1], "optional", "android.test.*.jar")))


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    parser.add_argument("--app", default=DEFAULT_APP, help=f"the minified app (default {DEFAULT_APP})")
    parser.add_argument("--test", default=DEFAULT_TEST, help=f"the test APK (default {DEFAULT_TEST})")
    parser.add_argument("--mapping", default=DEFAULT_MAPPING, help="the app's mapping directory")
    parser.add_argument("--platform", action="append", help="android.jar (default: the newest SDK platform)")
    args = parser.parse_args()

    jars = args.platform or default_platform_jars()
    inputs = [args.app, args.test] + [os.path.join(args.mapping, f) for f in ("mapping.txt", "usage.txt", "seeds.txt")]
    missing_inputs = [path for path in inputs + jars if not os.path.isfile(path)]
    if missing_inputs:
        print("check-test-apk: missing " + ", ".join(missing_inputs), file=sys.stderr)
        print("Build first: ./gradlew -Pheycam.testBuildType=release"
              " :app:assembleRelease :app:assembleReleaseAndroidTest", file=sys.stderr)
        return 2

    platform = Platform(jars)
    app_classes = read_apk(args.app)[0]
    test_classes, services = read_apk(args.test)
    mapping = Mapping(args.mapping)
    with open(os.path.join(args.mapping, "seeds.txt"), encoding="utf-8") as seeds_file:
        seeds = [line.rstrip("\n") for line in seeds_file]  # what the app's keep rules matched
    kept_members = {line for line in seeds if ": " in line}

    def lookup(descriptor):
        """Boot classpath first, as at run time; then the test APK and the app, which share one class loader."""
        info = platform.get(descriptor)
        if info is not None:
            return info, False
        if descriptor in test_classes:
            return test_classes[descriptor], False
        if descriptor in app_classes:
            return app_classes[descriptor], True
        return None, False

    def original(name):
        return mapping.classes.get(name, name)

    def removed_by_r8(descriptor):
        name = java_name(descriptor)
        return name in mapping.before_r8 or "R8$$REMOVED" in name

    # (original class name, whether R8 may rename it) -> member specifications; an empty set keeps just the class.
    missing = {}

    def check_class(descriptor):
        base = descriptor.lstrip("[")
        if base.startswith("L") and lookup(base)[0] is None and removed_by_r8(base):
            missing.setdefault((original(java_name(base)), True), set())

    def declaring_classes(owner, name, params):
        """The class R8 rules must name for owner.name: Kotlin's multifile facades (CollectionsKt) inherit their
        functions from parts (CollectionsKt___CollectionsKt). Also undoes the app's renaming of the member."""
        def family(cls):
            return cls == owner or cls.startswith(owner + "__")

        name = next((orig for cls, orig in mapping.renamed.get((name, params), ()) if family(cls)), name)
        parts = sorted(cls for cls in mapping.declared.get((name, params), ()) if family(cls))
        if parts and owner not in parts:
            missing.setdefault((owner, True), set())  # the facade the tests name must stay too
        return parts or [owner], name

    def check_member(kind, holder, member):
        if holder.startswith("[") or (kind == "method" and holder in SIGNATURE_POLYMORPHIC):
            return
        queue, seen, removed, in_app = [holder], set(), False, False
        while queue:
            current = queue.pop(0)
            if current is None or current in seen:
                continue
            seen.add(current)
            info, from_app = lookup(current)
            if info is None:
                if removed_by_r8(current):
                    check_class(current)
                    removed = True
                continue
            in_app = in_app or from_app
            if member in (info.methods if kind == "method" else info.fields):
                return
            queue.append(info.superclass)
            queue.extend(info.interfaces)
        if not (removed or in_app):
            return  # never the app's: an optional class a test library names
        owner = original(java_name(holder))
        if kind == "field":
            name, _, type_descriptor = member.partition(":")
            owners, name = declaring_classes(owner, name, None)
            spec = f"{java_types(type_descriptor, original)[0]} {name};"
        else:
            name, _, rest = member.partition("(")
            params_descriptor, _, return_descriptor = rest.partition(")")
            params = ",".join(java_types(params_descriptor, original))
            owners, name = declaring_classes(owner, name, params)
            spec = f"{java_types(return_descriptor, original)[0]} {name}({params});"
            # The test APK brings its own copy of a library class (Guava, say) and names it in the call; the app's
            # copy was renamed, so the signatures no longer meet. Keep that class's name in the app.
            foreign = {t.rstrip("[]") for t in java_types(params_descriptor + return_descriptor, lambda n: n)
                       if t.rstrip("[]") in mapping.before_r8 and t.rstrip("[]") not in mapping.classes}
            if foreign:
                for type_name in foreign:
                    missing.setdefault((type_name, False), set())
                return
        for declaring in owners:
            # Already kept and still not found: R8 wrote a mapping the test APK could not apply. Keep its name.
            renamable = f"{declaring}: {spec[:-1]}" not in kept_members
            missing.setdefault((declaring, renamable), set()).add(spec)

    # R8 may call the only implementation of an app type directly, or merge the two. A type the test APK extends or
    # implements must therefore be kept (seeds.txt) unless the app has implementations of its own besides.
    kept = {line.strip() for line in seeds if ":" not in line}
    app_subtypes = {}
    for info in app_classes.values():
        for parent in [info.superclass] + list(info.interfaces):
            app_subtypes[parent] = app_subtypes.get(parent, 0) + 1

    def check_supertype(parent):
        if lookup(parent)[1] and app_subtypes.get(parent, 0) < 2 and original(java_name(parent)) not in kept:
            missing.setdefault((original(java_name(parent)), True), set()).add("<methods>;")

    # A test class overrides an app method only while the two signatures agree. R8 may reorder the parameters of an
    # app method that nothing keeps: BaseContinuationImpl.create(Object, Continuation) became create(Continuation,
    # Object), so every suspend lambda in the test APK overrode nothing and runBlocking threw "create(Any?;
    # Continuation) has not been overridden". Same name and the same parameter types in another order is that.
    def check_overrides(info):
        ancestors, queue, seen = [], [info.superclass] + list(info.interfaces), set()
        while queue:
            current = queue.pop(0)
            if current is None or current in seen:
                continue
            seen.add(current)
            parent, from_app = lookup(current)
            if parent is None:
                continue
            if from_app:
                ancestors.append((current, parent))
            queue.append(parent.superclass)
            queue.extend(parent.interfaces)
        for method in info.methods:
            name, _, rest = method.partition("(")
            if name.startswith("<"):
                continue
            params_descriptor, _, return_descriptor = rest.partition(")")
            ours = java_types(params_descriptor, original)
            for holder, parent in ancestors:
                if method in parent.methods:
                    break  # a true override: nothing to keep
                for theirs in parent.methods:
                    their_name, _, their_rest = theirs.partition("(")
                    their_params = java_types(their_rest.partition(")")[0], original)
                    if their_name == name and their_params != ours and sorted(their_params) == sorted(ours):
                        params = ",".join(ours)
                        owners, original_name = declaring_classes(original(java_name(holder)), name, params)
                        spec = f"{java_types(return_descriptor, original)[0]} {original_name}({params});"
                        for declaring in owners:
                            missing.setdefault((declaring, True), set()).add(spec)

    # Every test APK class the instrumentation can reach, following its code from the roots. Services count only
    # where something can load them: the minified app keeps no ServiceLoader call unless it needs one.
    roots = [d for d in test_classes if d.startswith(ROOT_PACKAGES)]
    app_loads_services = calls_service_loader(app_classes)
    for service, implementations in services.items():
        if service in test_classes or (service in app_classes and app_loads_services):
            roots.extend(d for d in implementations if d in test_classes)
    pending, reached = roots, set()
    while pending:
        descriptor = pending.pop()
        if descriptor in reached:
            continue
        reached.add(descriptor)
        info = test_classes[descriptor]
        check_overrides(info)
        for parent in [info.superclass] + list(info.interfaces):
            if parent is not None:
                check_class(parent)
                check_supertype(parent)
                if parent in test_classes:
                    pending.append(parent)
        for use in info.uses:
            target = use[1].lstrip("[")
            if use[0] == "type":
                check_class(target)
            else:
                check_member(*use)
            if target in test_classes:
                pending.append(target)

    if missing:
        count = sum(len(members) or 1 for members in missing.values())
        print(f"check-test-apk: the release app lacks {count} thing(s) the device tests use."
              " Keep them in app/r8-test-entrypoints.pro, for example:")
        for cls, renamable in sorted(missing):
            keep = "-keep,allowobfuscation" if renamable else "-keep"
            members = sorted(missing[(cls, renamable)])
            if members:
                print(f"{keep} class {cls} {{")
                for member in members:
                    print(f"    {member}")
                print("}")
            else:
                print(f"{keep} class {cls}")
        return 1
    print(f"check-test-apk: the release app has everything the device tests use"
          f" ({len(reached)} of {len(test_classes)} test APK classes reached).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
