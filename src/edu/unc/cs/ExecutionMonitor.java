package edu.unc.cs;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.Set;

/**
 * 
 * This class implements a file system monitor to allow for other users to kill the gradings server and program.
 * The program watches a run-time specified file for deletion and runs a user specified kill script. If this program
 * is run by the user who started the grading server, then the kill script will be run with their permissions and 
 * thus be able to kill the grader and server even if the user deleting the file could not normally do so.
 * 
 * <p> If the monitor file does not exist when the program is started, it will be automatically created and likewise
 * will be remade after the kill script has finished executing. It will not, however, run if the kill script does not exist.
 * The contents of the kill script are important, unlike with the monitor file, and the proper method to kill the
 * grader server and program is dependent on both the host OS and specific setup so it must be created by the user.
 *
 * <p>At least on linux based systems, this will not work on networked drives. The {@link WatchService} class uses
 * inotify subsystem to detect changes, but these are only generated by changes on the local machine. For example,
 * the NFS system used at UNC will not register remote events since the NFS system does not signal events with inotify.
 * However, all changes on one machine, even if in a NFS space, will be registered since they go through the kernel,
 * and thus inotify, first then are shared by the NFS system. This could be worked around by instead polling the
 * file system directly for if the monitor file exists, but this would be much less efficient and for our uses it
 * is safe to assume all users will be on the same physical machine and local drives.
 * 
 * @author Andrew Vitkus
 *
 */
public class ExecutionMonitor {

	private static final boolean isPosix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

	public static final Set<PosixFilePermission> S_ug_rw, S_ug_rwx;
	public static final FileAttribute<?> FA_ug_rw, FA_ug_rwx;

	static {
		S_ug_rw = Collections.unmodifiableSet(PosixFilePermissions.fromString("rw-rw----"));
		FA_ug_rw = PosixFilePermissions.asFileAttribute(S_ug_rw);
		
    	S_ug_rwx = Collections.unmodifiableSet(PosixFilePermissions.fromString("rwxrwx---"));
    	FA_ug_rwx = PosixFilePermissions.asFileAttribute(S_ug_rwx);
	}

	
	public static void main(String[] args) {
		if (args.length < 1) {
			System.out.println("No monitor file or kill script specified");
			System.exit(-1);
		} else if (args.length < 2) {
			System.out.println("No kill script specified");
		}
		Path monitorFile = Paths.get(args[0]);
		Path fullMonitorPath = monitorFile.toAbsolutePath().normalize();
		Path killScript = Paths.get(args[1]);
		if(Files.notExists(killScript)) {
			System.out.println("Kill script does not exist");
			System.exit(-1);
		}
		Path monitorDir = fullMonitorPath.getParent();
		if (Files.notExists(fullMonitorPath)) {
			System.out.println("Creating monitor file");
			try {
				createRWXDirectories(monitorDir);
				createRWFile(fullMonitorPath);
			} catch (UnsupportedOperationException | SecurityException | IOException e) {
				System.out.println("Couldn't create monitor file");
			}
		}
		try {
			// create the file system watcher and set it to watch the directory containing
			// the montior file for deletions
			WatchService watcher = FileSystems.getDefault().newWatchService();
			monitorDir.register(watcher, StandardWatchEventKinds.ENTRY_DELETE);
			
			// keep checking for events until the program is interrupted
			while (!Thread.currentThread().isInterrupted()) {
				WatchKey key = null;
				// get a watched directory from file system watcher
				try {
					key = watcher.take();
				} catch (InterruptedException e) {
					System.exit(-1);
				}

				// look at all the events
				for (WatchEvent<?> event : key.pollEvents()) {
					WatchEvent.Kind<?> kind = event.kind();
					// if it is a deletion (all should be since we are only watching for these)
					if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
						@SuppressWarnings("unchecked")
						WatchEvent<Path> pEvent = (WatchEvent<Path>) event;
						Path deletedPath = pEvent.context().toAbsolutePath();
						// is the deleted file the monitor?
						if (fullMonitorPath.equals(deletedPath)) {
							System.out.println("Kill triggered");
							// run the kill script
							ProcessBuilder pb = new ProcessBuilder("bash", "-c", killScript.toString());
							pb.inheritIO();
							Process p = pb.start();
							// run the kill script till it returns or we are interrupted and should end
							try {
								p.waitFor();
							} catch (InterruptedException e) {
								System.exit(-1);
							} finally {
								System.out.println("Recreating monitor file");
								try {
									createRWFile(fullMonitorPath);
								} catch (UnsupportedOperationException | SecurityException | IOException e) {
									System.out.println("Couldn't recreate monitor file");
								}
							}
						}
					}
				}
				// mark the current monitor as finished and reset to get new events or die if no longer valid
				if (!key.reset()) {
					System.exit(-1);
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}

	/**
	 * Creates a file with rw-rw---- permissions
	 * 
	 * @param path file to create
	 * @return created file
	 * @throws UnsupportedOperationException
	 * @throws FileAlreadyExistsException
	 * @throws IOException
	 * @throws SecurityException
	 */
	private static Path createRWFile(Path path)
			throws UnsupportedOperationException, FileAlreadyExistsException, IOException, SecurityException {
		if (isPosix) {
			if (Files.notExists(path)) {
				Files.createFile(path, FA_ug_rw);
			}
			Files.setPosixFilePermissions(path, S_ug_rw);
			return path;
		} else {
			if (Files.notExists(path)) {
				Files.createFile(path);
			}
			File file = path.toFile();
			file.setReadable(true);
			file.setWritable(true);
			return path;
		}
	}


    /**
     * Recursively creates a directory with rwxrwx--- permissions.
     * 
     * @param path The directory to create
     * @return The created directory
     * @throws UnsupportedOperationException
     * @throws FileAlreadyExistsException
     * @throws IOException
     * @throws SecurityException
     */
    public static Path createRWXDirectories(Path path) throws UnsupportedOperationException, FileAlreadyExistsException, IOException, SecurityException {
    	if (isPosix) {
    		System.out.println("Creating posix directory: " + path);
        	return createRWXPosixDirectories(path);
    	} else {
    		System.out.println("Creating directory: " + path);
    		return createRWXDirectoriesNotPosix(path);
    	}
    }
    
    private static Path createRWXPosixDirectories(Path path) throws UnsupportedOperationException, FileAlreadyExistsException, IOException, SecurityException {
    	if (Files.exists(path)) {
    		return path;
    	}
    	Path parent = path.getParent();
    	if (!Files.exists(parent)) {
    		createRWXPosixDirectories(parent);
    	}
    	Files.createDirectory(path, FA_ug_rwx);
		Files.setPosixFilePermissions(path, S_ug_rwx);
		return path;
    }
    
    private static Path createRWXDirectoriesNotPosix(Path path) throws UnsupportedOperationException, FileAlreadyExistsException, IOException, SecurityException {
    	if (Files.exists(path)) {
    		return path;
    	}
    	Path parent = path.getParent();
    	if (!Files.exists(parent)) {
    		createRWXDirectoriesNotPosix(parent);
    	}
		Files.createDirectory(path);
		File dir = path.toFile();
		dir.setReadable(true);
		dir.setWritable(true);
		dir.setExecutable(true);
		return path;
    }
}
