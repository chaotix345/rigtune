package io.github.chaotix345.rigtune.core.jvm;

import org.jspecify.annotations.Nullable;

import java.util.List;

// What the JVM probe read (client/probe/JvmProbe): the input arguments (everything the JVM saw, JAVA_TOOL_OPTIONS
// included), the VM option lookup (null without HotSpot's diagnostic bean, e.g. on OpenJ9), the garbage collector bean
// names, Runtime.maxMemory(), InitialHeapSize (-1 if unknown), Runtime.version() and java.vendor. It lives only as long as
// the classification: the arguments can hold paths with the Windows user name, so toString leaves them out and nothing
// else keeps them.
public record JvmSnapshot(List<String> inputArguments, @Nullable VmOptions options, List<String> gcBeanNames, long maxHeapBytes,
		long initialHeapBytes, @Nullable String javaVersion, @Nullable String vendor) {
	public JvmSnapshot {
		inputArguments = inputArguments == null ? List.of() : inputArguments.stream().map(a -> a == null ? "" : a).toList();
		gcBeanNames = gcBeanNames == null ? List.of() : gcBeanNames.stream().map(a -> a == null ? "" : a).toList();
	}

	@Override
	public String toString() {
		return "JvmSnapshot[" + inputArguments.size() + " arguments, options " + (options == null ? "unavailable" : "available")
				+ ", gcBeans=" + gcBeanNames + ", maxHeapBytes=" + maxHeapBytes + ", initialHeapBytes=" + initialHeapBytes
				+ ", javaVersion=" + javaVersion + ", vendor=" + vendor + "]";
	}
}
