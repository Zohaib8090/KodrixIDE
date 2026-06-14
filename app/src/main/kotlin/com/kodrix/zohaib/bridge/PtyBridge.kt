package com.kodrix.zohaib.bridge

import android.os.Build
import android.system.Os
import android.util.Log
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.Field

class PtyBridge {
    private var masterFd: Int = -1
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    
    fun getFd(): Int = masterFd

    companion object {
        // NativeLibLoader.init() is called in MainActivity.onCreate
    }

    external fun createPty(shell: String, binPath: String, libPath: String, homePath: String, rows: Int, cols: Int): Int
    external fun setWindowSize(fd: Int, rows: Int, cols: Int)

    fun setupEnvironment(context: android.content.Context) {
        val binFile = java.io.File(context.filesDir, "bin")
        if (binFile.exists()) binFile.deleteRecursively()
        binFile.mkdirs()

        val libPath = context.applicationInfo.nativeLibraryDir
        setupSymlinks(context, binFile.absolutePath, libPath)
    }

    fun startShell(context: android.content.Context, shell: String = "/system/bin/sh", homeDir: String? = null, rows: Int = 24, cols: Int = 80) {
        setupEnvironment(context)
        
        val actualHome = homeDir ?: context.filesDir.absolutePath
        val libPath = context.applicationInfo.nativeLibraryDir
        masterFd = createPty(shell, context.filesDir.absolutePath, libPath, actualHome, rows, cols)
        
        if (masterFd != -1) {
            val fd = FileDescriptor()
            try {
                val field: Field = FileDescriptor::class.java.getDeclaredField("descriptor")
                field.isAccessible = true
                field.setInt(fd, masterFd)
                
                inputStream = FileInputStream(fd)
                outputStream = FileOutputStream(fd)
            } catch (e: Exception) {
                Log.e("PtyBridge", "Failed to create streams", e)
            }
        }
    }

    private fun extractGitTemplatesIfNeeded(context: android.content.Context) {
        val templatesDir = java.io.File(context.filesDir, "git_templates")
        val stampFile = java.io.File(templatesDir, ".extracted_v1")
        if (stampFile.exists()) return
        if (templatesDir.exists()) templatesDir.deleteRecursively()
        templatesDir.mkdirs()
        try {
            java.util.zip.ZipInputStream(context.assets.open("git-templates.zip")).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.replace('\\', '/')
                    val file = java.io.File(templatesDir, entryName)
                    if (entry.isDirectory || entryName.endsWith("/")) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        java.io.FileOutputStream(file).use { out ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                out.write(buffer, 0, len)
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            stampFile.createNewFile()
        } catch (e: Exception) {
            Log.e("PtyBridge", "Failed to extract Git templates", e)
        }
    }

    private fun extractNpmIfNeeded(context: android.content.Context) {
        val npmDir = java.io.File(context.filesDir, "npm_pkg")
        val stampFile = java.io.File(npmDir, ".extracted_v4")
        if (stampFile.exists()) return
        if (npmDir.exists()) npmDir.deleteRecursively()
        npmDir.mkdirs()
        try {
            java.util.zip.ZipInputStream(context.assets.open("npm.zip")).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.replace('\\', '/')
                    val file = java.io.File(npmDir, entryName)
                    if (entry.isDirectory || entryName.endsWith("/")) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        java.io.FileOutputStream(file).use { out ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                out.write(buffer, 0, len)
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            stampFile.createNewFile()
        } catch (e: Exception) {
            Log.e("PtyBridge", "Failed to extract NPM", e)
        }
    }

    private fun extractTunnelScript(context: android.content.Context) {
        val outFile = java.io.File(context.filesDir, "tunnel.js")
        try {
            context.assets.open("tunnel/tunnel.js").use { input ->
                outFile.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            Log.e("PtyBridge", "Failed to extract tunnel.js", e)
        }
    }

    private fun extractNextSwc(context: android.content.Context) {
        val usrDir = java.io.File(context.filesDir, "usr")
        val libDir = java.io.File(usrDir, "lib")
        libDir.mkdirs()
        val outFile = java.io.File(libDir, "next-swc.android-arm64.node")
        if (outFile.exists() && outFile.length() > 0) return
        try {
            java.util.zip.ZipInputStream(context.assets.open("next-swc.zip")).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.replace('\\', '/')
                    if (entryName == "next-swc.android-arm64.node") {
                        java.io.FileOutputStream(outFile).use { out ->
                            val buffer = ByteArray(8192)
                            var len: Int
                            while (zis.read(buffer).also { len = it } > 0) {
                                out.write(buffer, 0, len)
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e("PtyBridge", "Failed to extract next-swc", e)
        }
    }

    private fun setupSymlinks(context: android.content.Context, binPath: String, libPath: String) {
        extractNpmIfNeeded(context)
        extractGitTemplatesIfNeeded(context)
        extractTunnelScript(context)
        extractNextSwc(context)

        val filesDir   = context.filesDir.absolutePath
        val usrDir     = filesDir + "/usr"
        val usrBinDir  = usrDir + "/bin"
        val usrEtcDir  = usrDir + "/etc"
        val tmpDir     = filesDir + "/tmp"
        val cacheDir   = filesDir + "/cache"
        val libLinksDir = java.io.File(context.filesDir, "lib").absolutePath
        val nativeLibPath = context.applicationInfo.nativeLibraryDir
        
        java.io.File(usrBinDir).mkdirs()
        java.io.File(usrEtcDir).mkdirs()
        java.io.File(usrDir + "/lib").mkdirs()
        java.io.File(tmpDir).mkdirs()
        java.io.File(cacheDir).mkdirs()

        // Extract a dummy/system cert if needed
        val certFile = java.io.File(usrEtcDir, "cacert.pem")
        if (!certFile.exists()) {
            try {
                // Try to copy from system or just create an empty one to avoid ENOENT
                java.io.FileOutputStream(certFile).use { it.write("".toByteArray()) }
            } catch (e: Exception) {}
        }
        
        // Setup binary symlinks
        try {
            val ln = arrayOf("/system/bin/ln", "-sf")
            Runtime.getRuntime().exec(ln + arrayOf("$nativeLibPath/libgit_bin.so", "$usrBinDir/git")).waitFor()
            Runtime.getRuntime().exec(ln + arrayOf("$nativeLibPath/libgit_remote_http_bin.so", "$usrBinDir/git-remote-http")).waitFor()
            Runtime.getRuntime().exec(ln + arrayOf("$nativeLibPath/libgit_remote_http_bin.so", "$usrBinDir/git-remote-https")).waitFor()
            Runtime.getRuntime().exec(ln + arrayOf("$nativeLibPath/libnode_bin.so", "$usrBinDir/node")).waitFor()
        } catch (e: Exception) {
            Log.e("PtyBridge", "Failed to create symlinks", e)
        }

        // 1. DNS Configs
        try {
            val resolvConf = java.io.File(usrEtcDir, "resolv.conf")
            java.io.FileOutputStream(resolvConf).use { it.write("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n".toByteArray()) }

            val gitConfig = java.io.File(usrEtcDir, "gitconfig")
            // Always rewrite so new config keys are applied to existing installs
            java.io.FileOutputStream(gitConfig).use {
                it.write("[user]\n\tname = Kodrix Developer\n\temail = developer@kodrix.local\n[core]\n\tfileMode = false\n\tautocrlf = false\n\tattributesFile = $usrEtcDir/gitattributes\n[safe]\n\tdirectory = *\n[credential]\n\thelper =\n".toByteArray())
            }
            // Create empty gitattributes so Git never falls back to the Termux system path
            val gitAttributes = java.io.File(usrEtcDir, "gitattributes")
            if (!gitAttributes.exists()) gitAttributes.createNewFile()
            // Create empty credentials file with correct permissions (readable only by this app)
            val gitCreds = java.io.File(usrEtcDir, "git-credentials")
            if (!gitCreds.exists()) gitCreds.createNewFile()
            
            val dnsOverride = java.io.File(usrEtcDir, "dns-override.js")
            val dnsContent = """
                const dns = require('dns');
                const net = require('net');
                const fs = require('fs');
                const path = require('path');

                // Fix Next.js 16 realpathSync.native — must be before any fs patching
                if (typeof fs.realpathSync === 'function' && !fs.realpathSync.native) {
                    Object.defineProperty(fs.realpathSync, 'native', {
                        value: fs.realpathSync,
                        configurable: true,
                        enumerable: false,
                        writable: false
                    });
                }
                const { Resolver } = dns;
                const promises = dns.promises;
                const resolver = new Resolver();
                resolver.setServers(['8.8.8.8', '8.8.4.4', '1.1.1.1']);

                // --- DNS Patching ---
                const origLookup = dns.lookup;
                dns.lookup = function(hostname, options, callback) {
                  if (typeof options === 'function') { callback = options; options = {}; }
                  if (typeof options === 'number') { options = { family: options }; }
                  const family = (options && options.family) || 0;
                  const all = (options && options.all) || false;
                  
                  if (!hostname || hostname === 'localhost') {
                    const res = all ? [{address: '127.0.0.1', family: 4}] : '127.0.0.1';
                    return process.nextTick(() => callback(null, res, 4));
                  }
                  
                  if (net.isIP(hostname)) {
                    const ipFamily = net.isIPv4(hostname) ? 4 : 6;
                    const res = all ? [{address: hostname, family: ipFamily}] : hostname;
                    return process.nextTick(() => callback(null, res, ipFamily));
                  }
                  
                  const resolveMethod = (family === 6) ? 'resolve6' : 'resolve4';
                  resolver[resolveMethod](hostname, (err, addresses) => {
                    if (err || !addresses || addresses.length === 0) {
                      if (family === 0 && resolveMethod === 'resolve4') {
                        return resolver.resolve6(hostname, (err6, addresses6) => {
                          if (err6) return origLookup(hostname, options, callback);
                          finish(addresses6, 6);
                        });
                      }
                      return origLookup(hostname, options, callback);
                    }
                    finish(addresses, family === 6 ? 6 : 4);
                  });
                  
                  function finish(addresses, detectedFamily) {
                    if (all) {
                      const res = addresses.map(a => ({address: a, family: detectedFamily}));
                      return callback(null, res);
                    }
                    callback(null, addresses[0], detectedFamily);
                  }
                };
                
                if (promises && promises.lookup) {
                  promises.lookup = function(hostname, options) {
                    return new Promise((resolve, reject) => {
                      dns.lookup(hostname, options, (err, address, family) => {
                        if (err) return reject(err);
                        if (options && options.all) resolve(address);
                        else resolve({ address, family });
                      });
                    });
                  };
                }
                
                dns.resolve4 = (hostname, callback) => resolver.resolve4(hostname, callback);
                dns.resolve6 = (hostname, callback) => resolver.resolve6(hostname, callback);
                if (promises) {
                  promises.resolve4 = (hostname) => new Promise((resolve, reject) => resolver.resolve4(hostname, (err, addr) => err ? reject(err) : resolve(addr)));
                  promises.resolve6 = (hostname) => new Promise((resolve, reject) => resolver.resolve6(hostname, (err, addr) => err ? reject(err) : resolve(addr)));
                }

                // --- FS EACCES Fix for Watchpack/Android ---
                const APP_HOME = process.env.HOME || '';
                const RESTRICTED_ROOTS = [
                    '/data', '/proc', '/sys', '/dev', '/etc', '/sbin', '/bin', '/usr', '/var', 
                    '/apex', '/vendor', '/product', '/mnt', '/storage', '/sdcard', '/'
                ];
                
                function isRestricted(p) {
                    if (!p) return false;
                    let strP = p;
                    if (Buffer.isBuffer(p)) strP = p.toString();
                    if (typeof strP !== 'string') return false;
                    
                    try {
                        const resolved = path.resolve(strP);
                        // Allow everything inside APP_HOME
                        if (APP_HOME && (resolved === APP_HOME || resolved.startsWith(APP_HOME + '/'))) return false;
                        // Block known restricted roots
                        for (const root of RESTRICTED_ROOTS) {
                            if (root === '/') {
                                if (resolved === '/') return true;
                            } else if (resolved === root || resolved.startsWith(root + '/')) {
                                return true;
                            }
                        }
                    } catch(e) {}
                    return false;
                }

                function handleEacces(err, p) {
                    if (err && (err.code === 'EACCES' || err.code === 'EPERM' || err.code === 'EROFS')) {
                        err.code = 'ENOENT';
                        err.message = 'Permission denied (suppressed by CodeOSS for ' + (p || 'unknown') + ')';
                    }
                    return err;
                }

                function createFakeStats(isDir = true) {
                    const now = new Date();
                    return {
                        dev: 0, mode: isDir ? 16877 : 33188, nlink: 1, uid: 0, gid: 0, rdev: 0,
                        blksize: 4096, ino: 0, size: 0, blocks: 0,
                        atimeMs: now.getTime(), mtimeMs: now.getTime(), ctimeMs: now.getTime(), birthtimeMs: now.getTime(),
                        atime: now, mtime: now, ctime: now, birthtime: now,
                        isDirectory: () => isDir, isFile: () => !isDir, isBlockDevice: () => false,
                        isCharacterDevice: () => false, isSymbolicLink: () => false, isFIFO: () => false, isSocket: () => false
                    };
                }
                
                function createFakeDirent(name, isDir = true) {
                    return {
                        name: name,
                        isDirectory: () => isDir,
                        isFile: () => !isDir,
                        isBlockDevice: () => false,
                        isCharacterDevice: () => false,
                        isSymbolicLink: () => false,
                        isFIFO: () => false,
                        isSocket: () => false
                    };
                }

                // readdir
                const origReaddir = fs.readdir;
                fs.readdir = function(p, options, callback) {
                    if (typeof options === 'function') { callback = options; options = undefined; }
                    if (isRestricted(p)) return process.nextTick(() => callback(null, []));
                    origReaddir(p, options, (err, files) => {
                        if (err && (err.code === 'EACCES' || err.code === 'EPERM')) return callback(null, []);
                        callback(err, files);
                    });
                };
                const origReaddirSync = fs.readdirSync;
                fs.readdirSync = function(p, options) {
                    if (isRestricted(p)) return [];
                    try { return origReaddirSync(p, options); }
                    catch(err) { if(err.code === 'EACCES' || err.code === 'EPERM') return []; throw err; }
                };
                
                // opendir (Node 12+)
                const origOpendir = fs.opendir;
                if (origOpendir) {
                    fs.opendir = function(p, options, callback) {
                        if (typeof options === 'function') { callback = options; options = undefined; }
                        if (isRestricted(p)) {
                            const fakeDir = {
                                path: p,
                                read: (cb) => process.nextTick(() => cb(null, null)),
                                readSync: () => null,
                                close: (cb) => process.nextTick(() => cb(null)),
                                closeSync: () => {},
                                [Symbol.asyncIterator]: async function* () {}
                            };
                            return process.nextTick(() => callback(null, fakeDir));
                        }
                        origOpendir(p, options, callback);
                    };
                }
                const origOpendirSync = fs.opendirSync;
                if (origOpendirSync) {
                    fs.opendirSync = function(p, options) {
                        if (isRestricted(p)) {
                            return {
                                path: p,
                                readSync: () => null,
                                closeSync: () => {},
                                [Symbol.iterator]: function* () {}
                            };
                        }
                        return origOpendirSync(p, options);
                    };
                }

                // stat/lstat/access/realpath/readlink
                ['stat', 'lstat', 'access', 'realpath', 'readlink'].forEach(method => {
                    const orig = fs[method];
                    if (!orig) return;
                    fs[method] = function(...args) {
                        const p = args[0];
                        const callback = args[args.length - 1];
                        if (typeof callback === 'function') {
                            if (isRestricted(p)) {
                                if (method === 'stat' || method === 'lstat') return process.nextTick(() => callback(null, createFakeStats()));
                                if (method === 'access') return process.nextTick(() => callback(null));
                                if (method === 'realpath') return process.nextTick(() => callback(null, p));
                            }
                            const newArgs = args.slice(0, -1);
                            newArgs.push((err, ...res) => callback(err ? handleEacces(err, p) : null, ...res));
                            return orig.apply(fs, newArgs);
                        }
                        return orig.apply(fs, args);
                    };
                    const origSync = fs[method + 'Sync'];
                    if (origSync) {
                        fs[method + 'Sync'] = function(p, ...args) {
                            if (isRestricted(p)) {
                                if (method === 'stat' || method === 'lstat') return createFakeStats();
                                if (method === 'access') return;
                                if (method === 'realpath') return p;
                            }
                            try { return origSync.apply(fs, [p, ...args]); }
                            catch(err) { throw handleEacces(err, p); }
                        };
                    }
                });

                // watch/watchFile (suppress EACCES)
                const origWatch = fs.watch;
                fs.watch = function(p, ...args) {
                    if (isRestricted(p)) {
                        return { close: () => {}, on: () => {}, once: () => {}, emit: () => {}, addListener: () => {}, removeListener: () => {} };
                    }
                    try { return origWatch.apply(fs, [p, ...args]); }
                    catch(err) { 
                        return { close: () => {}, on: () => {}, once: () => {}, emit: () => {}, addListener: () => {}, removeListener: () => {} }; 
                    }
                };

                // Patch fs.promises
                if (fs.promises) {
                    ['readdir', 'stat', 'lstat', 'access', 'realpath', 'readlink', 'opendir'].forEach(method => {
                        const orig = fs.promises[method];
                        if (!orig) return;
                        fs.promises[method] = function(...args) {
                            const p = args[0];
                            if (isRestricted(p)) {
                                if (method === 'readdir') return Promise.resolve([]);
                                if (method === 'stat' || method === 'lstat') return Promise.resolve(createFakeStats());
                                if (method === 'access') return Promise.resolve();
                                if (method === 'realpath') return Promise.resolve(p);
                                if (method === 'opendir') return Promise.resolve({
                                    path: p,
                                    read: () => Promise.resolve(null),
                                    close: () => Promise.resolve(),
                                    [Symbol.asyncIterator]: async function* () {}
                                });
                            }
                            return orig.apply(this, args).catch(err => {
                                if (method === 'readdir' && (err.code === 'EACCES' || err.code === 'EPERM')) return [];
                                throw handleEacces(err, p);
                            });
                        };
                    });
                }
                
                // --- SWC / Next.js Android Native Fixes ---
                function sanitizeSwcOpts(obj) {
                    if (obj === null) return ""; 
                    if (typeof obj !== 'object') {
                        if (obj === undefined) return "";
                        return obj;
                    }
                    if (Array.isArray(obj)) return obj.map(v => (v === undefined || v === null) ? "" : sanitizeSwcOpts(v));
                    
                    const out = {};
                    for (const k of Object.getOwnPropertyNames(obj)) {
                        let v = obj[k];
                        if (v === undefined || v === null) {
                            out[k] = ""; // Force all missing/null fields to empty string for Rust
                        } else {
                            out[k] = sanitizeSwcOpts(v);
                        }
                    }
                    return out;
                }

                function wrapSwcFn(val, target, name) {
                    return function(...args) {
                        const sanitizedArgs = args.map(arg => {
                            if (Buffer.isBuffer(arg)) {
                                try {
                                    const str = arg.toString();
                                    if (str.trim().startsWith('{') || str.trim().startsWith('[')) {
                                        const parsed = JSON.parse(str);
                                        return Buffer.from(JSON.stringify(sanitizeSwcOpts(parsed)));
                                    }
                                    return arg;
                                } catch(e) { return arg; }
                            } else if (typeof arg === 'object' && arg !== null) {
                                return sanitizeSwcOpts(arg);
                            } else if (arg === undefined || arg === null) {
                                return "";
                            }
                            return arg;
                        });
                        try {
                            return val.apply(target, sanitizedArgs);
                        } catch(err) {
                            if (err.message && (err.message.includes('rust type') || err.message.includes('Undefined'))) {
                                console.error("[CodeOSS SWC] Native conversion error in " + (name || 'fn') + ": " + err.message);
                                return null;
                            }
                            throw err;
                        }
                    };
                }

                const Module = require('module');
                // Register .so as a native module extension. 
                // This is crucial on Android where native modules are often symlinked to .so files in jniLibs.
                if (!Module._extensions['.so']) {
                    Module._extensions['.so'] = Module._extensions['.node'];
                }
                const originalLoad = Module._load;
                const proxyCache = new WeakMap();
                Module._load = function(request, parent, isMain) {
                    // 1. EARLY BYPASS for Native Binaries (CRITICAL)
                    // We must NEVER wrap .node files in a Proxy. Node's internal loader
                    // expects the raw export object from the binary.
                    if (typeof request === 'string' && (
                        request.endsWith('.node') ||
                        request.includes('libbinding') ||
                        request.includes('next-swc') ||
                        request.includes('libnext_swc')
                    )) {
                        return originalLoad.apply(this, arguments);
                    }

                    const result = originalLoad.apply(this, arguments);

                    // 2. Only Proxy the JS wrappers for SWC
                    const shouldProxy = typeof request === 'string' && (
                        request.includes('@next/swc') ||
                        (request.includes('next-swc') && !request.endsWith('.node')) ||
                        request.includes('turbopack')
                    );

                    if (shouldProxy && result && (typeof result === 'object' || typeof result === 'function')) {
                        if (proxyCache.has(result)) return proxyCache.get(result);
                        try {
                            const proxy = new Proxy(result, {
                                apply(target, thisArg, argumentsList) {
                                    return wrapSwcFn(target, thisArg, 'default')(argumentsList);
                                },
                                get(target, prop) {
                                    const val = target[prop];
                                    if (typeof val === 'function') {
                                        return wrapSwcFn(val, target, prop.toString());
                                    }
                                    return val;
                                }
                            });
                            proxyCache.set(result, proxy);
                            return proxy;
                        } catch (e) {}
                    }
                    return result;
                };

            """.trimIndent()

            java.io.FileOutputStream(dnsOverride).use { it.write(dnsContent.toByteArray()) }
        } catch (e: Exception) {
            Log.e("PtyBridge", "Failed to create configs", e)
        }

        // 2. Generate init.sh
        val initScript = """
            export HOME="$filesDir"
            export TMPDIR="$tmpDir"
            export PREFIX="$usrDir"

            # ── Safe Mode / Emergency Rollback ────────────────────────────────
            # If the safe_mode flag file exists, route PATH to the static bundled-only
            # wrappers in usr/bin_safe/ instead of the dynamic usr/bin/ directory.
            # This lets the user recover from a broken runtime without losing terminal access.
            if [ -f "$filesDir/safe_mode" ]; then
                export PATH="$filesDir/usr/bin_safe:/system/bin:/system/xbin"
                echo "⚠️  Safe Mode active — using bundled runtimes only. Disable in Settings → Environment."
            else
                export PATH="$usrBinDir:/system/bin:/system/xbin"
            fi
            export OPENSSL_CONF="/dev/null"
            export RESOLV_CONF="$usrEtcDir/resolv.conf"
            export GIT_SSL_NO_VERIFY=true
            export WATCHPACK_POLLING=true
            export CHOKIDAR_USEPOLLING=1
            export WATCHPACK_POLLING_INTERVAL=100
            export GIT_TEMPLATE_DIR="$filesDir/git_templates"
            export GIT_CONFIG_NOSYSTEM=1
            export GIT_CONFIG_GLOBAL="$usrEtcDir/gitconfig"
            export GIT_EXEC_PATH="$nativeLibPath"
            export NATIVE_LIB_PATH="$nativeLibPath"
            export NEXT_DISABLE_SOURCEMAPS=1
            export SSL_CERT_FILE="$usrEtcDir/cacert.pem"
            export SSL_CERT_DIR="$usrEtcDir"
            export GRPC_DEFAULT_SSL_ROOTS_FILE_PATH="$usrEtcDir/cacert.pem"
            export APP_FILES_DIR="$filesDir"
            
            # Vite & Next.js Stability
            # NOTE: Do NOT add --preserve-symlinks here!
            # It breaks Next.js by keeping __dirname as .bin/ instead of dist/bin/,
            # causing require('../server/require-hook') to resolve to the wrong path.
            export NODE_OPTIONS="--require $usrEtcDir/dns-override.js"
            export NODE_PATH=".:${'$'}PWD/node_modules:$usrDir/lib/node_modules"
            export NEXT_TELEMETRY_DISABLED=1
            export NEXT_OTEL_FETCH_DISABLED=1
            export NEXT_PRIVATE_SKIP_SIZE_LIMIT=1
            
            # Note: Node, NPM, NPX, Git, and Git-Remote-HTTP(S) wrappers are now generated
            # natively via WrapperManager.kt. We do not generate them in init.sh anymore.
            
            cat << 'WRAPPER' > "$usrBinDir/gh"
            #!/system/bin/sh
            export LD_LIBRARY_PATH="$nativeLibPath:$libLinksDir"
            exec "$nativeLibPath/libgh_bin.so" "${'$'}@"
            WRAPPER
            chmod 755 "$usrBinDir/gh"
            
            cat << 'EOF' > "$usrBinDir/sh"
            #!/system/bin/sh
            if [ "$1" = "-c" ]; then
                target_cmd=$(echo "${'$'}2" | awk '{print ${'$'}1}')
                full_path=$(PATH="$usrBinDir:${'$'}PATH" command -v "${'$'}target_cmd")
                if [ -n "${'$'}full_path" ] && [ -f "${'$'}full_path" ]; then
                    first_line=$(head -n 1 "${'$'}full_path")
                    if echo "${'$'}first_line" | grep -qE "env node|bin/node"; then
                        shift 2
                        remaining_args=$(echo "${'$'}2" | cut -d' ' -f2-)
                        [ "${'$'}remaining_args" = "${'$'}target_cmd" ] && remaining_args=""
                        exec "$usrBinDir/node" "${'$'}full_path" ${'$'}remaining_args
                    fi
                fi
            fi
            exec /system/bin/sh "${'$'}@"
            EOF
            chmod 755 "$usrBinDir/sh"

            export PATH="$usrBinDir:${'$'}PATH"
            export LD_LIBRARY_PATH="$nativeLibPath:$libLinksDir"
            export SHELL="$usrBinDir/sh"
            export NPM_CONFIG_SHELL="$usrBinDir/sh"

            # Python Environment Integration
            if [ -f "$filesDir/active_python_version" ]; then
                export PYTHON_ACTIVE_VERSION=${'$'}(cat "$filesDir/active_python_version")
                export PYTHONHOME="$filesDir/versions/python/${'$'}PYTHON_ACTIVE_VERSION"
                export LD_LIBRARY_PATH="${'$'}PYTHONHOME/lib:${'$'}LD_LIBRARY_PATH"
            fi
            
            cat << 'EOF' > "$usrBinDir/git-credential-kodrix"
            #!/system/bin/sh
            if [ "${'$'}1" = "get" ]; then
                if [ -n "${'$'}KODRIX_GH_TOKEN" ]; then
                    echo "username=${'$'}{KODRIX_GH_USER:-kodrix}"
                    echo "password=${'$'}KODRIX_GH_TOKEN"
                fi
            fi
            EOF
            chmod 755 "$usrBinDir/git-credential-kodrix"

            # Force Git to use HTTPS instead of SSH
            git config --global url."https://github.com/".insteadOf "git@github.com:"
            git config --global url."https://".insteadOf "ssh://"
            git config --global core.autocrlf false
            git config --global core.attributesFile "$usrEtcDir/gitattributes"
            # Use dynamic credential helper via shell to bypass Android W^X restrictions securely
            git config --global credential.helper "!sh $usrBinDir/git-credential-kodrix"
            touch "$usrEtcDir/gitattributes"
            [ -z "$(git config --global user.name)" ] && git config --global user.name "Kodrix Developer"
            [ -z "$(git config --global user.email)" ] && git config --global user.email "developer@kodrix.local"

            # Generate CA bundle from Android system certs (fixes TLS for gh and other tools)
            if [ ! -s "$usrEtcDir/cacert.pem" ]; then
                cat /system/etc/security/cacerts/*.0 >> "$usrEtcDir/cacert.pem" 2>/dev/null || true
            fi

            # Note: We no longer write credentials to .netrc or hosts.yml for security reasons.
            # The GitHub token is passed via environment variables (KODRIX_GH_TOKEN) 
            # and handled dynamically by the git-credential-kodrix helper in-memory.

            _npm_fix() {
                if [ -d "node_modules" ]; then
                    
                    # 1. Fix permissions for all executables
                    find node_modules -type f -name "*" -executable -exec chmod 555 {} + 2>/dev/null || true
                    
                    # 2. Patch realpath.js in Next.js (CRITICAL for Next.js 15/16)
                    # Next.js sometimes uses its own realpath polyfill which fails on Android's restricted /data
                    # We force it to use our patched fs.realpathSync.native
                    find node_modules/next -name "realpath.js" 2>/dev/null | while read f; do
                        if ! grep -q "realpathSync.native" "${'$'}f"; then
                            echo "Patched Next.js realpath: ${'$'}f"
                            sed -i 's/fs.realpathSync/ (fs.realpathSync.native || fs.realpathSync)/g' "${'$'}f"
                        fi
                    done

                        # 3. Link Native SWC Binaries
                        if [ -d "node_modules/@next" ]; then
                            NEXT_PKG="node_modules/next/package.json"
                            [ -f "${'$'}NEXT_PKG" ] && NEXT_VER=${'$'}(grep '"version":' "${'$'}NEXT_PKG" | cut -d'"' -f4) || NEXT_VER="latest"
                            
                            SWC_ANDROID_DIR="node_modules/@next/swc-android-arm64"
                            mkdir -p "${'$'}SWC_ANDROID_DIR"
                            echo "Linking Native SWC Binaries for Next.js ${'$'}NEXT_VER..."
                            
                            # A. Production Priority: Use the Linux-compiled binary from jniLibs (bundled in APK)
                            # This binary is extracted to nativeLibraryDir by Android and has EXEC permissions.
                            if [ -f "${'$'}NATIVE_LIB_PATH/libnext_swc.so" ]; then
                                ln -sf "${'$'}NATIVE_LIB_PATH/libnext_swc.so" "${'$'}SWC_ANDROID_DIR/next-swc.android-arm64.node"
                                echo "Linked production SWC binary: libnext_swc.so"
                            fi

                            # B. Secondary: Link other bindings (HTML, Minifier, etc.) if they exist
                            for lib in "${'$'}NATIVE_LIB_PATH"/libbinding_*.so; do
                                [ -f "${'$'}lib" ] || continue
                                libname=${'$'}(basename "${'$'}lib" .so | sed 's/lib//')
                                case "${'$'}libname" in
                                    "binding_core_node") 
                                        # Only link if libnext_swc.so didn't already provide it
                                        [ -f "${'$'}SWC_ANDROID_DIR/next-swc.android-arm64.node" ] && continue
                                        target="next-swc.android-arm64.node" 
                                        ;;
                                    "binding_html_node") target="next-swc.android-arm64-html.node" ;;
                                    "binding_minifier_node") target="next-swc.android-arm64-minifier.node" ;;
                                    "binding_react_compiler_node") target="next-swc.android-arm64-react-compiler.node" ;;
                                    *) target="next-swc.android-arm64-${'$'}libname.node" ;;
                                esac
                                ln -sf "${'$'}lib" "${'$'}SWC_ANDROID_DIR/${'$'}target"
                            done
                            
                            # Fallback for manually pushed .node files in usr/lib
                            if [ ! -f "${'$'}SWC_ANDROID_DIR/next-swc.android-arm64.node" ] && [ -f "${'$'}usrDir/lib/next-swc.android-arm64.node" ]; then
                                ln -sf "${'$'}usrDir/lib/next-swc.android-arm64.node" "${'$'}SWC_ANDROID_DIR/next-swc.android-arm64.node"
                            fi
                            
                            printf '{"name":"@next/swc-android-arm64","version":"%s","main":"next-swc.android-arm64.node"}' "${'$'}NEXT_VER" > "${'$'}SWC_ANDROID_DIR/package.json"
                        fi

                    # 4. Create robust .bin/next JS proxy
                    if [ -d "node_modules/next" ]; then
                        mkdir -p "node_modules/.bin"
                        # Use rm -rf to force remove if it's a broken symlink or directory
                        rm -rf "node_modules/.bin/next" 2>/dev/null
                        
                        PROJECT_DIR="${'$'}PWD"
                        REAL_NEXT_BIN=""
                        for candidate in \
                            "${'$'}PROJECT_DIR/node_modules/next/dist/bin/next" \
                            "${'$'}PROJECT_DIR/node_modules/next/bin/next"; do
                            [ -f "${'$'}candidate" ] && REAL_NEXT_BIN="${'$'}candidate" && break
                        done
                        
                        if [ -n "${'$'}REAL_NEXT_BIN" ]; then
                            # Use absolute path require to bypass all relative path resolution issues
                            printf '#!/usr/bin/env node\nprocess.env.NEXT_TELEMETRY_DISABLED="1";\nprocess.env.WATCHPACK_POLLING="true";\nrequire("%s");\n' "${'$'}REAL_NEXT_BIN" > "node_modules/.bin/next"
                            chmod 755 "node_modules/.bin/next"
                            echo "Created node_modules/.bin/next JS proxy -> ${'$'}REAL_NEXT_BIN"
                        fi
                    fi
                fi
            }

            # npm wrapper: run _npm_fix AFTER npm finishes
            npm() {
                case "${'$'}1" in
                    install|i|ci|update|up)
                        # Check if installing/updating npm itself globally
                        is_global=0
                        is_npm_pkg=0
                        for arg in "${'$'}@"; do
                            if [ "${'$'}arg" = "-g" ] || [ "${'$'}arg" = "--global" ]; then
                                is_global=1
                            fi
                            case "${'$'}arg" in
                                npm|npm@*)
                                    is_npm_pkg=1
                                    ;;
                            esac
                        done

                        is_global_npm=0
                        if [ "${'$'}is_global" -eq 1 ] && [ "${'$'}is_npm_pkg" -eq 1 ]; then
                            is_global_npm=1
                        fi

                        if [ "${'$'}is_global_npm" -eq 1 ]; then
                            # Remove the wrappers temporarily so npm can write its symlinks/files without EEXIST
                            rm -f "$usrBinDir/npm" "$usrBinDir/npx"
                        fi

                        # Determine which npm script to run
                        ACTIVE_NODE_VER=""
                        if [ -f "${'$'}APP_FILES_DIR/active_node_version" ]; then
                            ACTIVE_NODE_VER=$(cat "${'$'}APP_FILES_DIR/active_node_version")
                        fi
                        
                        if [ -n "${'$'}ACTIVE_NODE_VER" ] && [ -f "${'$'}APP_FILES_DIR/versions/node/${'$'}ACTIVE_NODE_VER/lib/node_modules/npm/bin/npm-cli.js" ]; then
                            "$usrBinDir/node" "${'$'}APP_FILES_DIR/versions/node/${'$'}ACTIVE_NODE_VER/lib/node_modules/npm/bin/npm-cli.js" "${'$'}@"
                        elif [ -f "$usrDir/lib/node_modules/npm/bin/npm-cli.js" ]; then
                            "$usrBinDir/node" "$usrDir/lib/node_modules/npm/bin/npm-cli.js" "${'$'}@"
                        else
                            "$usrBinDir/node" "${'$'}APP_FILES_DIR/npm_pkg/bin/npm-cli.js" "${'$'}@"
                        fi
                        ret=$?

                        if [ "${'$'}is_global_npm" -eq 1 ]; then
                            # Recreate the wrappers!
                            _recreate_wrappers
                        fi

                        _npm_fix
                        return ${'$'}ret
                        ;;
                    *)
                        # Determine which npm script to run
                        ACTIVE_NODE_VER=""
                        if [ -f "${'$'}APP_FILES_DIR/active_node_version" ]; then
                            ACTIVE_NODE_VER=$(cat "${'$'}APP_FILES_DIR/active_node_version")
                        fi
                        
                        if [ -n "${'$'}ACTIVE_NODE_VER" ] && [ -f "${'$'}APP_FILES_DIR/versions/node/${'$'}ACTIVE_NODE_VER/lib/node_modules/npm/bin/npm-cli.js" ]; then
                            "$usrBinDir/node" "${'$'}APP_FILES_DIR/versions/node/${'$'}ACTIVE_NODE_VER/lib/node_modules/npm/bin/npm-cli.js" "${'$'}@"
                        elif [ -f "$usrDir/lib/node_modules/npm/bin/npm-cli.js" ]; then
                            "$usrBinDir/node" "$usrDir/lib/node_modules/npm/bin/npm-cli.js" "${'$'}@"
                        else
                            "$usrBinDir/node" "${'$'}APP_FILES_DIR/npm_pkg/bin/npm-cli.js" "${'$'}@"
                        fi
                        ;;
                esac
            }

            npx() {
                ACTIVE_NODE_VER=""
                if [ -f "${'$'}APP_FILES_DIR/active_node_version" ]; then
                    ACTIVE_NODE_VER=$(cat "${'$'}APP_FILES_DIR/active_node_version")
                fi
                
                if [ -n "${'$'}ACTIVE_NODE_VER" ] && [ -f "${'$'}APP_FILES_DIR/versions/node/${'$'}ACTIVE_NODE_VER/lib/node_modules/npm/bin/npx-cli.js" ]; then
                    "$usrBinDir/node" "${'$'}APP_FILES_DIR/versions/node/${'$'}ACTIVE_NODE_VER/lib/node_modules/npm/bin/npx-cli.js" "${'$'}@"
                elif [ -f "$usrDir/lib/node_modules/npm/bin/npx-cli.js" ]; then
                    "$usrBinDir/node" "$usrDir/lib/node_modules/npm/bin/npx-cli.js" "${'$'}@"
                else
                    "$usrBinDir/node" "${'$'}APP_FILES_DIR/npm_pkg/bin/npx-cli.js" "${'$'}@"
                fi
                _npm_fix
            }
            
            node() { "$usrBinDir/node" "${'$'}@"; }
            git() { "$usrBinDir/git" "${'$'}@"; }
            
            [ -d "node_modules" ] && _npm_fix

        """.trimIndent()

        try {
            java.io.File(filesDir, "init.sh").writeText(initScript)
        } catch (e: Exception) {
            Log.e("PtyBridge", "Failed to create init.sh", e)
        }
    }

    fun write(data: String) {
        try {
            outputStream?.write(data.toByteArray())
            outputStream?.flush()
        } catch (e: IOException) {
            Log.e("PtyBridge", "Write failed", e)
        }
    }

    fun read(buffer: ByteArray): Int {
        return try {
            inputStream?.read(buffer) ?: -1
        } catch (e: IOException) {
            Log.e("PtyBridge", "Read failed", e)
            -1
        }
    }

    fun resize(rows: Int, cols: Int) {
        if (masterFd != -1) {
            setWindowSize(masterFd, rows, cols)
        }
    }
}
