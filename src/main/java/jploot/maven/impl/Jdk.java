package jploot.maven.impl;

import java.nio.file.Path;

public class Jdk {

	private final Path location;
	private final boolean found;

	public Jdk(Path location, boolean found) {
		this.location = location;
		this.found = found;
	}

	public boolean isFound() {
		return found;
	}

	public Path javaHome() {
		if (!found) {
			throw new IllegalStateException(String.format("JDK not found %s", location));
		}
		return location;
	}

	public Path java() {
		if (!found) {
			throw new IllegalStateException(String.format("JDK not found %s", location));
		}
		return location.resolve("bin").resolve("java");
	}

	public Path jlink() {
		if (!found) {
			throw new IllegalStateException(String.format("JDK not found %s", location));
		}
		return location.resolve("bin").resolve("jlink");
	}

	public Path jmods() {
		if (!found) {
			throw new IllegalStateException(String.format("JDK not found %s", location));
		}
		return location.resolve("jmods");
	}


}
