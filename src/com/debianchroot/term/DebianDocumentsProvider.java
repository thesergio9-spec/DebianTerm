package com.debianchroot.term;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Storage Access Framework (SAF) DocumentsProvider exposing DebianTerm's non-root PRoot /root
 * directory to external Android file managers and code editors (Material Files, Acode, QuickEdit, etc.).
 */
public final class DebianDocumentsProvider extends DocumentsProvider {

    public static final String AUTHORITY = "com.debianchroot.term.documents";
    public static final String ROOT_ID_HOME = "debianterm_home";
    public static final String DOC_ID_PREFIX = "home:";

    private static final String[] DEFAULT_ROOT_PROJECTION = {
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_AVAILABLE_BYTES
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = {
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE
    };

    private static final Map<String, String> LINUX_MIME_TYPES = buildMimeMap();

    private static Map<String, String> buildMimeMap() {
        Map<String, String> m = new HashMap<>();
        m.put("sh", "text/x-shellscript");
        m.put("bash", "text/x-shellscript");
        m.put("zsh", "text/x-shellscript");
        m.put("py", "text/x-python");
        m.put("js", "application/javascript");
        m.put("mjs", "application/javascript");
        m.put("ts", "text/plain");
        m.put("json", "application/json");
        m.put("xml", "application/xml");
        m.put("yml", "text/yaml");
        m.put("yaml", "text/yaml");
        m.put("md", "text/markdown");
        m.put("txt", "text/plain");
        m.put("log", "text/plain");
        m.put("conf", "text/plain");
        m.put("cfg", "text/plain");
        m.put("ini", "text/plain");
        m.put("env", "text/plain");
        m.put("properties", "text/plain");
        m.put("java", "text/x-java-source");
        m.put("c", "text/x-c");
        m.put("h", "text/x-c");
        m.put("cpp", "text/x-c++src");
        m.put("rs", "text/plain");
        m.put("go", "text/plain");
        m.put("html", "text/html");
        m.put("htm", "text/html");
        m.put("css", "text/css");
        m.put("csv", "text/csv");
        m.put("sql", "application/sql");
        m.put("tar", "application/x-tar");
        m.put("gz", "application/gzip");
        m.put("tgz", "application/gzip");
        m.put("xz", "application/x-xz");
        m.put("zip", "application/zip");
        m.put("pdf", "application/pdf");
        m.put("png", "image/png");
        m.put("jpg", "image/jpeg");
        m.put("jpeg", "image/jpeg");
        m.put("webp", "image/webp");
        m.put("svg", "image/svg+xml");
        m.put("ttf", "font/ttf");
        m.put("otf", "font/otf");
        return Collections.unmodifiableMap(m);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    private File getHomeBaseDir() throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null) {
            throw new FileNotFoundException("Contexto no disponible");
        }
        return new File(ProotManager.getRootfsDir(ctx), "root");
    }

    private boolean isRootfsPresent() {
        Context ctx = getContext();
        if (ctx == null) return false;
        File rootfs = ProotManager.getRootfsDir(ctx);
        return rootfs.isDirectory() && new File(rootfs, "bin/bash").exists();
    }

    private File resolveOrThrow(String documentId) throws FileNotFoundException {
        File baseDir = getHomeBaseDir();
        if (DOC_ID_PREFIX.equals(documentId) && !baseDir.exists() && isRootfsPresent()) {
            //noinspection ResultOfMethodCallIgnored
            baseDir.mkdirs();
        }
        File target = resolveDocumentFile(baseDir, documentId);
        if (target == null || !target.exists()) {
            throw new FileNotFoundException("Documento no encontrado: " + documentId);
        }
        return target;
    }

    @Override
    public Cursor queryRoots(String[] projection) throws FileNotFoundException {
        Context ctx = getContext();
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        MatrixCursor.RowBuilder row = result.newRow();

        boolean installed = isRootfsPresent();
        long availableBytes = (ctx != null) ? ctx.getFilesDir().getUsableSpace() : 0L;

        row.add(Root.COLUMN_ROOT_ID, ROOT_ID_HOME);
        row.add(Root.COLUMN_MIME_TYPES, "*/*");
        row.add(Root.COLUMN_FLAGS,
            Root.FLAG_SUPPORTS_CREATE | Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_ICON, R.drawable.ic_launcher);
        row.add(Root.COLUMN_TITLE, "DebianTerm (Home)");
        row.add(Root.COLUMN_SUMMARY, installed
            ? "/root (Debian 12 Sin Root)"
            : "Instala Debian 12 Sin Root primero");
        row.add(Root.COLUMN_DOCUMENT_ID, DOC_ID_PREFIX);
        row.add(Root.COLUMN_AVAILABLE_BYTES, availableBytes);
        return result;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        File baseDir = getHomeBaseDir();
        if (DOC_ID_PREFIX.equals(documentId) && !baseDir.exists()) {
            if (isRootfsPresent() && baseDir.mkdirs()) {
                includeFile(result, DOC_ID_PREFIX, baseDir, "Home (/root)", true);
                return result;
            }
            MatrixCursor.RowBuilder row = result.newRow();
            row.add(Document.COLUMN_DOCUMENT_ID, DOC_ID_PREFIX);
            row.add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR);
            row.add(Document.COLUMN_DISPLAY_NAME, "Home (/root - Sin instalar)");
            row.add(Document.COLUMN_LAST_MODIFIED, System.currentTimeMillis());
            row.add(Document.COLUMN_FLAGS, 0);
            row.add(Document.COLUMN_SIZE, 0L);
            return result;
        }
        File file = resolveOrThrow(documentId);
        boolean isRoot = DOC_ID_PREFIX.equals(documentId);
        includeFile(result, documentId, file, isRoot ? "Home (/root)" : file.getName(), isRoot);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(
        String parentDocumentId,
        String[] projection,
        String sortOrder
    ) throws FileNotFoundException {
        MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        File baseDir = getHomeBaseDir();
        if (DOC_ID_PREFIX.equals(parentDocumentId) && !baseDir.exists() && !isRootfsPresent()) {
            Context ctx = getContext();
            if (ctx != null) {
                Uri uri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId);
                result.setNotificationUri(ctx.getContentResolver(), uri);
            }
            return result;
        }

        File parent = resolveOrThrow(parentDocumentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("El documento padre no es un directorio: " + parentDocumentId);
        }
        File[] children = parent.listFiles();
        if (children != null) {
            Arrays.sort(children, (a, b) -> {
                boolean ad = a.isDirectory();
                boolean bd = b.isDirectory();
                if (ad != bd) return ad ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            for (File child : children) {
                String docId = buildDocumentId(baseDir, child);
                if (docId == null) {
                    // External symlink pointing outside PRoot /root -> hide safely
                    continue;
                }
                if (resolveDocumentFile(baseDir, docId) == null) {
                    continue;
                }
                includeFile(result, docId, child, child.getName(), false);
            }
        }
        Context ctx = getContext();
        if (ctx != null) {
            Uri uri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId);
            result.setNotificationUri(ctx.getContentResolver(), uri);
        }
        return result;
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        try {
            File baseDir = getHomeBaseDir();
            File parent = resolveDocumentFile(baseDir, parentDocumentId);
            File child = resolveDocumentFile(baseDir, documentId);
            if (parent == null || child == null) {
                return false;
            }
            String parentCanon = parent.getCanonicalPath();
            String childCanon = child.getCanonicalPath();
            String parentPrefix = parentCanon.endsWith(File.separator)
                ? parentCanon
                : parentCanon + File.separator;
            return childCanon.equals(parentCanon) || childCanon.startsWith(parentPrefix);
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = resolveOrThrow(documentId);
        return guessMimeType(file);
    }

    @Override
    public ParcelFileDescriptor openDocument(
        String documentId,
        String mode,
        CancellationSignal signal
    ) throws FileNotFoundException {
        File file = resolveOrThrow(documentId);
        if (file.isDirectory()) {
            throw new FileNotFoundException("No se puede abrir un directorio como flujo: " + documentId);
        }
        int accessMode = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(file, accessMode);
    }

    @Override
    public String createDocument(
        String parentDocumentId,
        String mimeType,
        String displayName
    ) throws FileNotFoundException {
        if (!isValidChildName(displayName)) {
            throw new IllegalArgumentException("Nombre de archivo inválido: " + displayName);
        }
        File baseDir = getHomeBaseDir();
        if (!baseDir.exists() && DOC_ID_PREFIX.equals(parentDocumentId) && isRootfsPresent()) {
            //noinspection ResultOfMethodCallIgnored
            baseDir.mkdirs();
        }
        File parent = resolveOrThrow(parentDocumentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("El padre no es un directorio: " + parentDocumentId);
        }
        boolean createDir = Document.MIME_TYPE_DIR.equals(mimeType);
        File target = deduplicateFile(parent, displayName, createDir);

        // Verify target resolves cleanly inside baseDir
        String newDocId = buildLogicalDocId(baseDir, parent, target.getName());
        if (newDocId == null) {
            throw new FileNotFoundException("Ruta destino inválida");
        }

        if (createDir) {
            if (!target.mkdir()) {
                throw new FileNotFoundException("No se pudo crear carpeta: " + target.getName());
            }
            TarExtractor.setMode(target, 0755);
        } else {
            try {
                if (!target.createNewFile()) {
                    throw new FileNotFoundException("No se pudo crear archivo: " + target.getName());
                }
                TarExtractor.setMode(target, 0644);
            } catch (IOException e) {
                throw new FileNotFoundException("Error creando archivo: " + e.getMessage());
            }
        }
        notifyChildChange(parentDocumentId);
        return newDocId;
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        if (DOC_ID_PREFIX.equals(documentId)) {
            throw new IllegalArgumentException("No se puede renombrar la raíz Home");
        }
        if (!isValidChildName(displayName)) {
            throw new IllegalArgumentException("Nombre inválido: " + displayName);
        }
        File baseDir = getHomeBaseDir();
        File src = resolveOrThrow(documentId);
        File parent = src.getParentFile();
        if (parent == null) {
            throw new FileNotFoundException("Sin directorio padre");
        }
        File dst = new File(parent, displayName);
        if (dst.exists() || TarExtractor.isSymlink(dst)) {
            throw new IllegalStateException("Ya existe un elemento llamado: " + displayName);
        }
        if (!src.renameTo(dst)) {
            throw new FileNotFoundException("No se pudo renombrar " + src.getName());
        }
        String parentDocId = buildDocumentId(baseDir, parent);
        if (parentDocId != null) {
            notifyChildChange(parentDocId);
        }
        String newDocId = buildLogicalDocId(baseDir, parent, dst.getName());
        if (newDocId == null) {
            throw new FileNotFoundException("No se pudo construir ID tras renombrar");
        }
        return newDocId;
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if (DOC_ID_PREFIX.equals(documentId)) {
            throw new IllegalArgumentException("No se puede eliminar la raíz Home (/root)");
        }
        File baseDir = getHomeBaseDir();
        File target = resolveOrThrow(documentId);
        File parent = target.getParentFile();
        // deleteRecursivelyNoFollow safely unlinks symlinks without traversing targets
        if (!TarExtractor.deleteRecursivelyNoFollow(target)) {
            throw new FileNotFoundException("No se pudo eliminar: " + target.getName());
        }
        if (parent != null) {
            String parentDocId = buildDocumentId(baseDir, parent);
            if (parentDocId != null) {
                notifyChildChange(parentDocId);
            }
        }
    }

    private void includeFile(
        MatrixCursor result,
        String docId,
        File file,
        String displayName,
        boolean isRoot
    ) {
        int flags = 0;
        if (file.isDirectory()) {
            flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
            if (!isRoot) {
                flags |= Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
            }
        } else {
            flags |= Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE | Document.FLAG_SUPPORTS_RENAME;
        }

        MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_MIME_TYPE, guessMimeType(file));
        row.add(Document.COLUMN_DISPLAY_NAME, displayName);
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_SIZE, file.isDirectory() ? 0L : file.length());
    }

    private void notifyChildChange(String parentDocId) {
        Context ctx = getContext();
        if (ctx != null && parentDocId != null) {
            Uri uri = DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocId);
            ctx.getContentResolver().notifyChange(uri, null, false);
        }
    }

    private static File deduplicateFile(File parent, String rawName, boolean isDir) {
        File candidate = new File(parent, rawName);
        if (!candidate.exists() && !TarExtractor.isSymlink(candidate)) {
            return candidate;
        }
        String base = rawName;
        String ext = "";
        if (!isDir) {
            int dot = rawName.lastIndexOf('.');
            if (dot > 0) {
                base = rawName.substring(0, dot);
                ext = rawName.substring(dot);
            }
        }
        for (int n = 1; n < 1000; n++) {
            File alt = new File(parent, base + " (" + n + ")" + ext);
            if (!alt.exists() && !TarExtractor.isSymlink(alt)) {
                return alt;
            }
        }
        return candidate;
    }

    private static boolean isValidChildName(String name) {
        if (name == null || name.isEmpty()) return false;
        if (".".equals(name) || "..".equals(name)) return false;
        return name.indexOf('/') < 0 && name.indexOf('\\') < 0 && name.indexOf('\0') < 0;
    }

    private static String buildLogicalDocId(File baseDir, File parent, String childName) {
        String parentDocId = buildDocumentId(baseDir, parent);
        if (parentDocId == null || !isValidChildName(childName)) return null;
        if (DOC_ID_PREFIX.equals(parentDocId)) {
            return DOC_ID_PREFIX + childName;
        }
        return parentDocId + "/" + childName;
    }

    /**
     * Resolves a SAF documentId safely inside baseDir, blocking path traversal and external symlinks.
     */
    public static File resolveDocumentFile(File baseDir, String documentId) {
        if (baseDir == null || documentId == null || !documentId.startsWith(DOC_ID_PREFIX)) {
            return null;
        }
        String rel = documentId.substring(DOC_ID_PREFIX.length());
        if (rel.isEmpty()) {
            return baseDir;
        }
        if (rel.indexOf('\0') >= 0 || rel.startsWith("/") || rel.endsWith("/") || rel.indexOf('\\') >= 0) {
            return null;
        }
        String[] segs = rel.split("/", -1);
        for (String seg : segs) {
            if (seg.isEmpty() || ".".equals(seg) || "..".equals(seg)) {
                return null;
            }
        }
        try {
            String baseCanon = baseDir.getCanonicalPath();
            String basePrefix = baseCanon.endsWith(File.separator) ? baseCanon : baseCanon + File.separator;

            File cur = baseDir;
            for (String seg : segs) {
                cur = new File(cur, seg);
                if (cur.exists() || TarExtractor.isSymlink(cur)) {
                    String canon = cur.getCanonicalPath();
                    if (!canon.equals(baseCanon) && !canon.startsWith(basePrefix)) {
                        return null;
                    }
                    if (TarExtractor.isSymlink(cur)) {
                        String symTarget = TarExtractor.readSymlinkTarget(cur);
                        if (symTarget != null) {
                            File symTargetFile = symTarget.startsWith("/")
                                ? new File(symTarget)
                                : new File(cur.getParentFile(), symTarget);
                            String targetCanon = symTargetFile.getCanonicalPath();
                            if (!targetCanon.equals(baseCanon) && !targetCanon.startsWith(basePrefix)) {
                                return null;
                            }
                        }
                    }
                }
            }
            String finalCanon = cur.getCanonicalPath();
            if (!finalCanon.equals(baseCanon) && !finalCanon.startsWith(basePrefix)) {
                return null;
            }
            return cur;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Builds a canonical documentId for a file inside baseDir, returning null if it escapes baseDir.
     */
    public static String buildDocumentId(File baseDir, File file) {
        if (baseDir == null || file == null) {
            return null;
        }
        try {
            String baseCanon = baseDir.getCanonicalPath();
            String basePrefix = baseCanon.endsWith(File.separator) ? baseCanon : baseCanon + File.separator;
            String canon = file.getCanonicalPath();
            if (!canon.equals(baseCanon) && !canon.startsWith(basePrefix)) {
                return null;
            }
            if (TarExtractor.isSymlink(file)) {
                String symTarget = TarExtractor.readSymlinkTarget(file);
                if (symTarget != null) {
                    File symTargetFile = symTarget.startsWith("/")
                        ? new File(symTarget)
                        : new File(file.getParentFile(), symTarget);
                    String targetCanon = symTargetFile.getCanonicalPath();
                    if (!targetCanon.equals(baseCanon) && !targetCanon.startsWith(basePrefix)) {
                        return null;
                    }
                }
                File parent = file.getParentFile();
                if (parent == null) return null;
                String parentDocId = buildDocumentId(baseDir, parent);
                if (parentDocId == null) return null;
                return DOC_ID_PREFIX.equals(parentDocId)
                    ? (DOC_ID_PREFIX + file.getName())
                    : (parentDocId + "/" + file.getName());
            }
            if (canon.equals(baseCanon)) {
                return DOC_ID_PREFIX;
            }
            String rel = canon.substring(basePrefix.length()).replace(File.separatorChar, '/');
            return DOC_ID_PREFIX + rel;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Determines MIME type safely on both Android and host JVM (where MimeTypeMap may be stubbed).
     */
    public static String guessMimeType(File file) {
        if (file == null) return "application/octet-stream";
        if (file.isDirectory()) {
            return Document.MIME_TYPE_DIR;
        }
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot + 1 < name.length()) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.US);
            String mapped = LINUX_MIME_TYPES.get(ext);
            if (mapped != null) {
                return mapped;
            }
            try {
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime != null && !mime.isEmpty()) {
                    return mime;
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Host JVM fallback
            }
        }
        return "application/octet-stream";
    }
}
