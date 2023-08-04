/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.wire

import com.squareup.wire.kotlin.RpcCallStyle
import com.squareup.wire.kotlin.RpcRole
import com.squareup.wire.schema.CustomTarget
import com.squareup.wire.schema.JavaTarget
import com.squareup.wire.schema.KotlinTarget
import com.squareup.wire.schema.Location
import com.squareup.wire.schema.SwiftTarget
import com.squareup.wire.schema.Target
import com.squareup.wire.schema.WIRE_RUNTIME_JAR
import com.squareup.wire.schema.WireRun
import com.squareup.wire.schema.isWireRuntimeProto
import com.squareup.wire.schema.newEventListenerFactory
import com.squareup.wire.schema.newSchemaHandler
import com.squareup.wire.schema.toOkioFileSystem
import java.io.IOException
import java.nio.file.FileSystem as NioFileSystem
import okio.FileNotFoundException
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip

/**
 * Command line interface to the Wire Java generator.
 *
 * Usage
 * -----
 *
 * ```
 * java WireCompiler --proto_path=<path>
 *   [--java_out=<path>]
 *   [--kotlin_out=<path>]
 *   [--swift_out=<path>]
 *   [--custom_out=<path>]
 *   [--schema_handler_factory_class=<class_name>]
 *   [--files=<protos.include>]
 *   [--includes=<message_name>[,<message_name>...]]
 *   [--excludes=<message_name>[,<message_name>...]]
 *   [--android]
 *   [--android-annotations]
 *   [--compact]
 *   [file [file...]]
 * ```
 *
 * `--java_out` should provide the folder where the files generated by the Java code generator
 * should be placed. Similarly, `--kotlin_out` should provide the folder where the files generated
 * by the Kotlin code generator will be written. Only one of the two should be specified.
 *
 * `--swift_out` should provide the folder where the files generated by the Swift code generator
 * should be placed.
 *
 * `--event_listener_factory_class` should be used if you want to add a
 * [EventListener][com.squareup.wire.schema.EventListener]. The factory class itself should be
 * included in your classpath.
 *
 * `--schema_handler_factory_class` should be used if you want a custom
 * [SchemaHandler][com.squareup.wire.schema.SchemaHandler] to be called. The factory class itself should be included in
 * your classpath. If set, `custom_out` should also be provided and will passed to the factory's handler as a location
 * to where it will be able to write files.
 *
 * If the `--includes` flag is present, its argument must be a comma-separated list of
 * fully-qualified message or enum names. The output will be limited to those messages and enums
 * that are (transitive) dependencies of the listed names. The `--excludes` flag excludes types, and
 * takes precedence over `--includes`.
 *
 * If the `--registry_class` flag is present, its argument must be a Java class name. A class with
 * the given name will be generated, containing a constant list of all extension classes generated
 * during the compile. This list is suitable for passing to Wire's constructor at runtime for
 * constructing its internal extension registry.
 *
 * The `--dry_run` flag causes the compiler to just emit the names of the source files that would be
 * generated to stdout.
 *
 * The `--android` flag will cause all messages to implement the `Parcelable`
 * interface. This implies `--android-annotations` as well.
 *
 * The `--android-annotations` flag will add the `Nullable` annotation to optional fields.
 *
 * The `--compact` flag will emit code that uses reflection for reading, writing, and
 * toString methods which are normally implemented with code generation.
 */
class WireCompiler internal constructor(
  val fs: FileSystem,
  val log: WireLogger,
  val protoPaths: List<String>,
  val javaOut: String?,
  val kotlinOut: String?,
  val swiftOut: String?,
  val customOut: String?,
  val schemaHandlerFactoryClass: String?,
  val sourceFileNames: List<String>,
  val treeShakingRoots: List<String>,
  val treeShakingRubbish: List<String>,
  val modules: Map<String, WireRun.Module>,
  val emitAndroid: Boolean,
  val emitAndroidAnnotations: Boolean,
  val emitCompact: Boolean,
  val emitDeclaredOptions: Boolean,
  val emitAppliedOptions: Boolean,
  val permitPackageCycles: Boolean,
  val javaInterop: Boolean,
  val kotlinBoxOneOfsMinSize: Int,
  val kotlinExclusive: Boolean,
  val kotlinRpcCallStyle: RpcCallStyle,
  val kotlinRpcRole: RpcRole,
  val kotlinSingleMethodServices: Boolean,
  val kotlinGrpcServerCompatible: Boolean,
  val kotlinNameSuffix: String?,
  val kotlinBuildersOnly: Boolean,
  val eventListenerFactoryClasses: List<String>,
) {

  @Throws(IOException::class)
  fun compile() {
    val targets = mutableListOf<Target>()
    if (javaOut != null) {
      targets += JavaTarget(
        outDirectory = javaOut,
        android = emitAndroid,
        androidAnnotations = emitAndroidAnnotations,
        compact = emitCompact,
        emitDeclaredOptions = emitDeclaredOptions,
        emitAppliedOptions = emitAppliedOptions,
      )
    } else if (kotlinOut != null) {
      targets += KotlinTarget(
        exclusive = kotlinExclusive,
        outDirectory = kotlinOut,
        android = emitAndroid,
        javaInterop = javaInterop,
        emitDeclaredOptions = emitDeclaredOptions,
        emitAppliedOptions = emitAppliedOptions,
        rpcCallStyle = kotlinRpcCallStyle,
        rpcRole = kotlinRpcRole,
        singleMethodServices = kotlinSingleMethodServices,
        boxOneOfsMinSize = kotlinBoxOneOfsMinSize,
        grpcServerCompatible = kotlinGrpcServerCompatible,
        nameSuffix = kotlinNameSuffix,
        buildersOnly = kotlinBuildersOnly,
      )
    } else if (swiftOut != null) {
      targets += SwiftTarget(
        outDirectory = swiftOut,
      )
    } else if (customOut != null || schemaHandlerFactoryClass != null) {
      if (customOut == null || schemaHandlerFactoryClass == null) {
        throw IllegalArgumentException("Both custom_out and schema_handler_factory_class need to be set")
      }
      targets += CustomTarget(
        outDirectory = customOut,
        schemaHandlerFactory = newSchemaHandler(schemaHandlerFactoryClass),
      )
    }

    val sources = protoPaths.map { it.toPath() }

    val allDirectories = sources.map { Location.get(it.toString()) }.toList()
    val sourcePath: List<Location>
    val protoPath: List<Location>

    if (sourceFileNames.isNotEmpty()) {
      sourcePath = sourceFileNames.map { locationOfProto(sources, it) }
      protoPath = allDirectories
    } else {
      sourcePath = allDirectories
      protoPath = listOf()
    }

    val wireRun = WireRun(
      sourcePath = sourcePath,
      protoPath = protoPath,
      treeShakingRoots = treeShakingRoots,
      treeShakingRubbish = treeShakingRubbish,
      targets = targets,
      modules = modules,
      permitPackageCycles = permitPackageCycles,
      eventListeners = eventListenerFactoryClasses.map { newEventListenerFactory(it).create() },
    )

    wireRun.execute(fs, log)
  }

  /** Searches [sources] trying to resolve [proto]. Returns the location if it is found. */
  private fun locationOfProto(sources: List<Path>, proto: String): Location {
    // We cache ZIP openings because they are expensive.
    val sourceToZipFileSystem = mutableMapOf<Path, FileSystem>()
    for (source in sources) {
      if (fs.metadataOrNull(source)?.isRegularFile == true) {
        sourceToZipFileSystem[source] = fs.openZip(source)
      }
    }

    val directoryEntry = sources.find { source ->
      when (val zip = sourceToZipFileSystem[source]) {
        null -> fs.exists(source / proto)
        else -> zip.exists("/".toPath() / proto)
      }
    }

    if (directoryEntry == null) {
      if (isWireRuntimeProto(proto)) return Location.get(WIRE_RUNTIME_JAR, proto)
      throw FileNotFoundException("Failed to locate $proto in $sources")
    }

    return Location.get(directoryEntry.toString(), proto)
  }

  companion object {
    const val CODE_GENERATED_BY_WIRE =
      "Code generated by Wire protocol buffer compiler, do not edit."

    private const val PROTO_PATH_FLAG = "--proto_path="
    private const val JAVA_OUT_FLAG = "--java_out="
    private const val KOTLIN_OUT_FLAG = "--kotlin_out="
    private const val SWIFT_OUT_FLAG = "--swift_out="
    private const val CUSTOM_OUT_FLAG = "--custom_out="
    private const val SCHEMA_HANDLER_FACTORY_CLASS_FLAG = "--schema_handler_factory_class="
    private const val FILES_FLAG = "--files="
    private const val INCLUDES_FLAG = "--includes="
    private const val EXCLUDES_FLAG = "--excludes="
    private const val MANIFEST_FLAG = "--experimental-module-manifest="
    private const val EVENT_LISTENER_FACTORY_CLASS_FLAG = "--event_listener_factory_class="
    private const val ANDROID = "--android"
    private const val ANDROID_ANNOTATIONS = "--android-annotations"
    private const val COMPACT = "--compact"
    private const val SKIP_DECLARED_OPTIONS = "--skip_declared_options"
    private const val SKIP_APPLIED_OPTIONS = "--skip_applied_options"
    private const val PERMIT_PACKAGE_CYCLES_OPTIONS = "--permit_package_cycles"
    private const val JAVA_INTEROP = "--java_interop"
    private const val DRY_RUN = "--dry_run"
    private const val KOTLIN_BOX_ONEOFS_MIN_SIZE = "--kotlin_box_oneofs_min_size="
    private const val KOTLIN_EXCLUSIVE = "--kotlin_exclusive"
    private const val KOTLIN_RPC_CALL_STYLE = "--kotlin_rpc_call_style"
    private const val KOTLIN_RPC_ROLE = "--kotlin_rpc_role"
    private const val KOTLIN_SINGLE_METHOD_SERVICES = "--kotlin_single_method_services"
    private const val KOTLIN_GRPC_SERVER_COMPATIBLE = "--kotlin_grpc_server_compatible"
    private const val KOTLIN_NAMESUFFIX = "--kotlin_namesuffix"
    private const val KOTLIN_BUILDERS_ONLY = "--kotlin_builders_only"

    @Throws(IOException::class)
    @JvmStatic
    fun main(args: Array<String>) {
      try {
        val wireCompiler = forArgs(args = args)
        wireCompiler.compile()
      } catch (e: WireException) {
        System.err.print("Fatal: ")
        e.printStackTrace(System.err)
        System.exit(1)
      }
    }

    @Throws(WireException::class)
    @JvmStatic
    fun forArgs(
      fileSystem: NioFileSystem,
      logger: WireLogger,
      vararg args: String,
    ): WireCompiler {
      return forArgs(fileSystem.toOkioFileSystem(), logger, *args)
    }

    @Throws(WireException::class)
    @JvmOverloads
    @JvmStatic
    fun forArgs(
      fileSystem: FileSystem = FileSystem.SYSTEM,
      logger: WireLogger = ConsoleWireLogger(),
      vararg args: String,
    ): WireCompiler {
      val sourceFileNames = mutableListOf<String>()
      val treeShakingRoots = mutableListOf<String>()
      val treeShakingRubbish = mutableListOf<String>()
      val protoPaths = mutableListOf<String>()
      var modules = mapOf<String, WireRun.Module>()
      var javaOut: String? = null
      var kotlinOut: String? = null
      var swiftOut: String? = null
      var customOut: String? = null
      val eventListenerFactoryClasses = mutableListOf<String>()
      var schemaHandlerFactoryClass: String? = null
      var emitAndroid = false
      var emitAndroidAnnotations = false
      var emitCompact = false
      var emitDeclaredOptions = true
      var emitAppliedOptions = true
      var permitPackageCycles = false
      var javaInterop = false
      var kotlinBoxOneOfsMinSize = 5_000
      var kotlinExclusive = true
      var kotlinRpcCallStyle = RpcCallStyle.SUSPENDING
      var kotlinRpcRole = RpcRole.CLIENT
      var kotlinSingleMethodServices = false
      var kotlinGrpcServerCompatible = false
      var kotlinNameSuffix: String? = null
      var kotlinBuildersOnly = false
      var dryRun = false

      for (arg in args) {
        when {
          arg.startsWith(PROTO_PATH_FLAG) -> {
            protoPaths.add(arg.substring(PROTO_PATH_FLAG.length))
          }

          arg.startsWith(JAVA_OUT_FLAG) -> {
            check(javaOut == null) { "java_out already set" }
            javaOut = arg.substring(JAVA_OUT_FLAG.length)
          }

          arg.startsWith(KOTLIN_OUT_FLAG) -> {
            check(kotlinOut == null) { "kotlin_out already set" }
            kotlinOut = arg.substring(KOTLIN_OUT_FLAG.length)
          }

          arg.startsWith(KOTLIN_BOX_ONEOFS_MIN_SIZE) -> {
            kotlinBoxOneOfsMinSize = arg.substring(KOTLIN_BOX_ONEOFS_MIN_SIZE.length).toInt()
          }

          arg.startsWith(KOTLIN_EXCLUSIVE) -> {
            kotlinExclusive = arg.substring(KOTLIN_EXCLUSIVE.length).toBoolean()
          }
          arg.startsWith(KOTLIN_RPC_CALL_STYLE) -> {
            kotlinRpcCallStyle = RpcCallStyle.valueOf(arg.substring(KOTLIN_RPC_CALL_STYLE.length).uppercase())
          }
          arg.startsWith(KOTLIN_RPC_ROLE) -> {
            kotlinRpcRole = RpcRole.valueOf(arg.substring(KOTLIN_RPC_ROLE.length).uppercase())
          }
          arg.startsWith(KOTLIN_SINGLE_METHOD_SERVICES) -> {
            kotlinSingleMethodServices = arg.substring(KOTLIN_SINGLE_METHOD_SERVICES.length).toBoolean()
          }
          arg.startsWith(KOTLIN_GRPC_SERVER_COMPATIBLE) -> {
            kotlinGrpcServerCompatible = arg.substring(KOTLIN_GRPC_SERVER_COMPATIBLE.length).toBoolean()
          }
          arg.startsWith(KOTLIN_NAMESUFFIX) -> {
            kotlinNameSuffix = arg.substring(KOTLIN_NAMESUFFIX.length)
          }
          arg.startsWith(KOTLIN_BUILDERS_ONLY) -> {
            kotlinBuildersOnly = arg.substring(KOTLIN_BUILDERS_ONLY.length).toBoolean()
          }

          arg.startsWith(SWIFT_OUT_FLAG) -> {
            swiftOut = arg.substring(SWIFT_OUT_FLAG.length)
          }

          arg.startsWith(CUSTOM_OUT_FLAG) -> {
            customOut = arg.substring(CUSTOM_OUT_FLAG.length)
          }

          arg.startsWith(EVENT_LISTENER_FACTORY_CLASS_FLAG) -> {
            eventListenerFactoryClasses.add(arg.substring(EVENT_LISTENER_FACTORY_CLASS_FLAG.length))
          }

          arg.startsWith(SCHEMA_HANDLER_FACTORY_CLASS_FLAG) -> {
            schemaHandlerFactoryClass = arg.substring(SCHEMA_HANDLER_FACTORY_CLASS_FLAG.length)
          }

          arg.startsWith(FILES_FLAG) -> {
            val files = arg.substring(FILES_FLAG.length).toPath()
            try {
              fileSystem.read(files) {
                while (true) {
                  val line = readUtf8Line() ?: break
                  sourceFileNames.add(line)
                }
              }
            } catch (ex: FileNotFoundException) {
              throw WireException("Error processing argument $arg", ex)
            }
          }

          arg.startsWith(INCLUDES_FLAG) -> {
            treeShakingRoots += arg.substring(INCLUDES_FLAG.length).split(Regex(","))
          }

          arg.startsWith(EXCLUDES_FLAG) -> {
            treeShakingRubbish += arg.substring(EXCLUDES_FLAG.length).split(Regex(","))
          }

          arg.startsWith(MANIFEST_FLAG) -> {
            val yaml = fileSystem.read(arg.substring(MANIFEST_FLAG.length).toPath()) { readUtf8() }
            modules = parseManifestModules(yaml)
          }

          arg == ANDROID -> emitAndroid = true
          arg == ANDROID_ANNOTATIONS -> emitAndroidAnnotations = true
          arg == COMPACT -> emitCompact = true
          arg == DRY_RUN -> dryRun = true
          arg == SKIP_DECLARED_OPTIONS -> emitDeclaredOptions = false
          arg == SKIP_APPLIED_OPTIONS -> emitAppliedOptions = false
          arg == PERMIT_PACKAGE_CYCLES_OPTIONS -> permitPackageCycles = true
          arg == JAVA_INTEROP -> javaInterop = true
          arg.startsWith("--") -> throw IllegalArgumentException("Unknown argument '$arg'.")
          else -> sourceFileNames.add(arg)
        }
      }

      if (javaOut == null && kotlinOut == null && swiftOut == null && customOut == null) {
        throw WireException(
          "Nothing to do! Specify $JAVA_OUT_FLAG, $KOTLIN_OUT_FLAG, $SWIFT_OUT_FLAG, or $CUSTOM_OUT_FLAG",
        )
      }

      if (treeShakingRoots.isEmpty()) {
        treeShakingRoots += "*"
      }

      return WireCompiler(
        fs = if (dryRun) DryRunFileSystem(fileSystem) else fileSystem,
        log = logger,
        protoPaths = protoPaths,
        javaOut = javaOut,
        kotlinOut = kotlinOut,
        swiftOut = swiftOut,
        customOut = customOut,
        schemaHandlerFactoryClass = schemaHandlerFactoryClass,
        sourceFileNames = sourceFileNames,
        treeShakingRoots = treeShakingRoots,
        treeShakingRubbish = treeShakingRubbish,
        modules = modules,
        emitAndroid = emitAndroid,
        emitAndroidAnnotations = emitAndroidAnnotations,
        emitCompact = emitCompact,
        emitDeclaredOptions = emitDeclaredOptions,
        emitAppliedOptions = emitAppliedOptions,
        permitPackageCycles = permitPackageCycles,
        javaInterop = javaInterop,
        kotlinBoxOneOfsMinSize = kotlinBoxOneOfsMinSize,
        kotlinExclusive = kotlinExclusive,
        kotlinRpcCallStyle = kotlinRpcCallStyle,
        kotlinRpcRole = kotlinRpcRole,
        kotlinSingleMethodServices = kotlinSingleMethodServices,
        kotlinGrpcServerCompatible = kotlinGrpcServerCompatible,
        kotlinNameSuffix = kotlinNameSuffix,
        kotlinBuildersOnly = kotlinBuildersOnly,
        eventListenerFactoryClasses = eventListenerFactoryClasses,
      )
    }
  }
}
