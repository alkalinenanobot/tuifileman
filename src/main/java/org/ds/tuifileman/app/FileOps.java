package org.ds.tuifileman.app;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** File system write operations; each returns an error message or null on success. */
final class FileOps {
    private FileOps() { }

    static String validateName(String name) {
        if (name.isBlank()) {
            return "Name cannot be empty";
        }
        if (name.equals(".") || name.equals("..")) {
            return "Invalid name";
        }
        for (char c : "<>:\"/\\|?*".toCharArray()) {
            if (name.indexOf(c) >= 0) {
                return "Name cannot contain " + c;
            }
        }
        if (name.endsWith(" ") || name.endsWith(".")) {
            return "Name cannot end with a space or dot";
        }
        return null;
    }

    static String createDirectory(Path parent, String name) {
        String invalid = validateName(name);
        if (invalid != null) {
            return invalid;
        }
        try {
            Files.createDirectory(parent.resolve(name));
            return null;
        } catch (FileAlreadyExistsException e) {
            return "\"" + name + "\" already exists";
        } catch (AccessDeniedException e) {
            return "Access denied";
        } catch (InvalidPathException e) {
            return "Invalid name";
        } catch (IOException e) {
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }
}
