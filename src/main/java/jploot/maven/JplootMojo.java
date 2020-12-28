package jploot.maven;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;

import jploot.maven.impl.Jdk;

@Mojo(name = "jploot", defaultPhase = LifecyclePhase.PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME, requiresOnline = true, requiresProject = true)
@Execute(phase = LifecyclePhase.PACKAGE)
public class JplootMojo extends AbstractMojo {

	private static final String JPLOOT_FOLDER = "jploot";

	@Parameter(defaultValue = "${project.build.directory}/jploot/", required = true)
	private String outputDirectory;

	@Parameter(required = true)
	private String mainClass;

	@Parameter(required = true)
	private String scriptName;

	@Parameter
	private List<String> modules;

	@Parameter
	private List<String> jlinkOptions;

	@Parameter
	private MavenSession mavenSession;

	@Parameter
	private String args;

	@Parameter(defaultValue = "${project.artifactId}-${project.version}.run", required = true)
	private String finalName;

	@Parameter(defaultValue = "false", property = "jploot.verbose", required = true)
	private boolean verbose;

	@Parameter(defaultValue = "false", property = "jploot.skip", required = true)
	private boolean skip;

	@Parameter(defaultValue = "true", property = "jploot.attach", required = true)
	private boolean attach;

	@Parameter(defaultValue = "${project}", readonly = true)
	private MavenProject project;

	@Component
	private MavenProjectHelper projectHelper;

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		if (skip) {
			getLog().info("Skipping");
		}
		try {
			Path outputDirectoryPath = Path.of(outputDirectory);
			
			if (!outputDirectoryPath.toFile().exists()) {
				outputDirectoryPath.toFile().mkdirs();
			}
			
			Jdk jdk = resolveJdk();
			if (!jdk.isFound()) {
				String message = String.format("Expected JDK %s is missing", jdk.javaHome());
				getLog().error(message);
				throw new MojoFailureException(message);
			}
			
			Path jplootHome = outputDirectoryPath.resolve("archive-root");
			Path jreDirectory = jreDirectory(jplootHome);
			buildJre(jdk, jreDirectory);
			stripJre(jreDirectory);
			addJploot(jplootHome);
			
			List<String> makeselfCommand = new ArrayList<>();
			Path makeselfArchive = outputDirectoryPath.resolve(finalName);
			
			makeselfCommand.add("makeself");
			makeselfCommand.add(jplootHome.toAbsolutePath().toString());
			makeselfCommand.add(makeselfArchive.toAbsolutePath().toString());
			makeselfCommand.add(finalName);
			makeselfCommand.add(Path.of("bin", scriptName).toString());
			runCommand(makeselfCommand);
			if (attach) {
				projectHelper.attachArtifact(project, "run", null, makeselfArchive.toFile());
			}
		} catch (RuntimeException | InterruptedException | IOException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			throw new MojoExecutionException("Unexpected failure", e);
		}
	}

	void verboseLog(String message) {
		if (verbose) {
			getLog().info(message);
		} else {
			getLog().debug(message);
		}
	}

	/**
	 * Add jploot/ folder with jploot's dependencies. Create a jploot-installer file.
	 */
	private void addJploot(Path jplootHome) throws IOException {
		// prepare JPLOOT_HOME directory
		// jvm/ + jploot/
		Path jplootSubfolder = Path.of(JPLOOT_FOLDER);
		Path targetApplication = jplootHome.resolve(jplootSubfolder);
		File targetApplicationFile = targetApplication.toFile();
		if (!targetApplicationFile.exists()) {
			targetApplicationFile.mkdirs();
		}
		
		// collect artifacts
		verboseLog("Installed application artifacts:");
		List<String> classpath = new ArrayList<>();
		for (Artifact artifact : project.getArtifacts()) {
			if (artifact.getArtifactHandler().isAddedToClasspath()) {
				File file = artifact.getFile();
				Path target = targetApplication.resolve(file.getName());
				getLog().debug(String.format("copy %s to %s", file.getAbsolutePath(), target));
				Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
				classpath.add(file.getName());
				verboseLog(String.format("%s (%s)", file.getName(), artifact));
			} else {
				verboseLog(String.format("Ignored artifact: %s", artifact));
			}
		}
		
		// create installer script
		String launcherScript;
		String resourcePath = "/jploot-installer/launcher";
		launcherScript = readResourceToString(resourcePath);
		String classpathString = classpath.stream()
				.map(s -> String.format("\"$JPLOOT_HOME\"/%s/%s", jplootSubfolder, escape(s)))
				.collect(Collectors.joining(":"));
		launcherScript = launcherScript.replace(
				"[[CLASSPATH]]",
				classpathString
				);
		launcherScript = launcherScript.replace(
				"[[MAINCLASS]]",
				mainClass
				);
		launcherScript = launcherScript.replace(
				"[[ARGS]]",
				args != null ? args : ""
				);
		Path binDir = jplootHome.resolve("bin");
		binDir.toFile().mkdirs();
		Path launcher = binDir.resolve(scriptName);
		
		// install script and set execution permission
		Files.writeString(
				launcher,
				launcherScript,
				StandardOpenOption.CREATE,
				StandardOpenOption.TRUNCATE_EXISTING);
		Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
		verboseLog(String.format("JRE launcher script added: %s", launcher.toAbsolutePath()));
	}

	private void stripJre(Path jreDirectory) throws IOException, InterruptedException, MojoFailureException {
		List<String> stripCommand = new ArrayList<>();
		stripCommand.add("strip");
		stripCommand.add("-p");
		stripCommand.add("--strip-unneeded");
		try (Stream<Path> soFiles = Files.walk(jreDirectory.resolve("lib"))) {
			soFiles.filter(p -> p.toFile().isFile() && p.getFileName().toString().endsWith(".so"))
				.map(Path::toAbsolutePath)
				.map(Path::toString)
				.forEach(stripCommand::add);
		}
		runCommand(stripCommand);
		
		verboseLog("JRE stripped down");
	}

	private void buildJre(Jdk jdk, Path jreDirectory) throws IOException, InterruptedException, MojoFailureException {
		// TODO: use a better condition
		if (jreDirectory.toFile().exists()) {
			getLog().warn(String.format("JRE already present: %s; skipped creation", jreDirectory));
			return;
		}
		if (jreDirectory.toFile().exists()) {
			verboseLog(String.format("Cleaning existing directory %s", jreDirectory));
			FileUtils.deleteDirectory(jreDirectory.toFile());
		}
		List<String> command = new ArrayList<>();
		command.add(jdk.jlink().toAbsolutePath().toString());
		command.add("-v");
		if (jlinkOptions != null) {
			jlinkOptions.stream().forEach(command::add);
		}
		command.add("--module-path");
		command.add(jdk.jmods().toAbsolutePath().toString());
		// TODO: which module to include; empty modules is not an option
		if (modules != null) {
			command.add("--add-modules");
			command.add(String.join(",", modules));
		}
		command.add("--output");
		command.add(jreDirectory.toAbsolutePath().toString());
		runCommand(command);
		
		Path targetJava = jreDirectory.resolve("bin").resolve("java");
		if (!validate(targetJava, JplootMojo::isExecutableFile)) {
			String message = String.format(
					"Unexpected missing executable file %s after a successful jlink building",
					targetJava);
			getLog().error(message);
			throw new MojoFailureException(message);
		}
		
		verboseLog(String.format("JRE generated: %s", jreDirectory));
	}

	private static String readResourceToString(String resourcePath) throws IOException {
		try (InputStream is = JplootMojo.class.getResourceAsStream(resourcePath)) {
			return IOUtil.toString(is);
		}
	}

	private void runCommand(List<String> command) throws IOException, InterruptedException, MojoFailureException {
		String commandString = commandAsString(command);
		
		verboseLog(String.format("Executing %s", commandString));
		
		ProcessBuilder builder = new ProcessBuilder(command);
		if (verbose) {
			builder.inheritIO();
		} else {
			builder.redirectError(Redirect.DISCARD).redirectOutput(Redirect.DISCARD);
		}
		Process p = builder.start();
		int result = p.waitFor();
		if (result != 0) {
			String message = String.format("Command %s failed with status %d",
					commandString,
					result);
			getLog().error(message);
			throw new MojoFailureException(message);
		}
	}

	private static String commandAsString(List<String> command) {
		return command.stream().map(JplootMojo::escape).collect(Collectors.joining(" "));
	}

	private static String escape(String unescaped) {
		return "'" + unescaped.replace("'", "'\"'\"'") + "'";
	}

	private static Path jreDirectory(Path outputDirectory) {
		return outputDirectory.resolve("jvm");
	}

	private static Jdk resolveJdk() {
		String javaHome = System.getProperty("java.home", null);
		if (javaHome == null) {
			return new Jdk(null, false);
		}
		Path javaHomePath = Path.of(javaHome);
		Jdk jdk = new Jdk(javaHomePath, true);
		if (validate(jdk.javaHome(), File::isDirectory)
				&& validate(jdk.java(), JplootMojo::isExecutableFile)
				&& validate(jdk.jlink(), JplootMojo::isExecutableFile)) {
			return jdk;
		} else {
			return new Jdk(javaHomePath, false);
		}
	}

	private static boolean validate(Path path, Predicate<File> conditions) {
		File file = path.toFile();
		return conditions.test(file);
	}

	private static boolean isExecutableFile(File file) {
		if (!file.isFile()) {
			return false;
		}
		if (!file.canExecute()) {
			return false;
		}
		return true;
	}
}
