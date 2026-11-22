# R8 for the release: unused code gone and the rest optimized (Compose above all is far slower without),
# names kept as they are, so a crash's stack and the system's trace still read as the code.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable

# The terminal's native code (libtermux.so) binds to these by their names.
-keep class com.termux.terminal.** { *; }
