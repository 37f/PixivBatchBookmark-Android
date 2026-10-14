package com.openai.pixivbatchbookmark;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.UUID;

/** App-private, bounded recovery/export files. No Android dependency or credentials. */
public final class RecoveryStore {
    public static final int MAX_TXT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_STATE_BYTES = 8 * 1024 * 1024;
    private static final Set<String> SNAPSHOT_KEYS = new LinkedHashSet<>(Arrays.asList(
            "input", "results", "log", "ids", "private", "remove", "index", "active", "wallpaperOnly",
            "loginStatus", "ok", "converted", "skip", "invalid", "fail", "progress"));
    private static final int ENVELOPE_V1 = 0x50585631;
    private static final Map<String, Coordinator> DIRECTORIES = new HashMap<>();
    private static final class Coordinator {
        long generation, revision, committedRevision;
        Owner owner;
        Snapshot latest;
        String lease;
        Owner leaseOwner;
        boolean providerInFlight;
    }
    public static final class Owner {
        private final long generation;
        private boolean retired;
        private Owner(long generation) { this.generation = generation; }
    }
    public static final class Snapshot {
        private final Owner owner;
        private final long revision;
        private final Map<String, String> values;
        private Snapshot(Owner owner, long revision, Map<String, String> values) {
            this.owner = owner; this.revision = revision; this.values = new HashMap<>(values);
        }
    }
    public static final class Export {
        private final String id;
        private final int requestCode;
        private Export(String id, int requestCode) { this.id = id; this.requestCode = requestCode; }
        public String getId() { return id; }
        public int getRequestCode() { return requestCode; }
    }
    private final Coordinator coordinator;
    private final File directory;
    private final List<String> recoveryWarnings = new ArrayList<>();

    public RecoveryStore(File directory) throws IOException {
        this.directory = directory.getCanonicalFile();
        synchronized (DIRECTORIES) {
            Coordinator found = DIRECTORIES.get(this.directory.getPath());
            if (found == null) { found = new Coordinator(); DIRECTORIES.put(this.directory.getPath(), found); }
            coordinator = found;
        }
        synchronized (coordinator) {
            if (!this.directory.isDirectory() && !this.directory.mkdirs()) throw new IOException("无法建立恢复文件目录");
        }
    }

    public Owner claimOwner() {
        synchronized (coordinator) {
            if (coordinator.owner != null) coordinator.owner.retired = true;
            if (coordinator.lease != null && !coordinator.providerInFlight) releaseLease();
            coordinator.owner = new Owner(++coordinator.generation);
            coordinator.revision = 0; coordinator.committedRevision = 0; coordinator.latest = null;
            return coordinator.owner;
        }
    }

    public void retireOwner(Owner owner) {
        synchronized (coordinator) {
            owner.retired = true;
            if (coordinator.leaseOwner == owner && !coordinator.providerInFlight) releaseLease();
        }
    }

    public boolean isOwner(Owner owner) {
        synchronized (coordinator) { return owns(owner); }
    }
    private boolean owns(Owner owner) { return owner != null && coordinator.owner == owner && !owner.retired; }

    public Snapshot captureSnapshot(Owner owner, Map<String, String> values) {
        synchronized (coordinator) {
            if (!owns(owner)) return null;
            if (!SNAPSHOT_KEYS.containsAll(values.keySet())) throw new IllegalArgumentException("恢复快照不能包含登录凭据或未知字段");
            coordinator.latest = new Snapshot(owner, ++coordinator.revision, values);
            return coordinator.latest;
        }
    }
    public boolean commitSnapshot(Snapshot snapshot) throws IOException {
        synchronized (coordinator) {
            if (snapshot == null || !owns(snapshot.owner) || snapshot.revision != coordinator.revision) return false;
            if (snapshot.revision > coordinator.committedRevision) {
                saveSnapshotValues(snapshot.values);
                coordinator.committedRevision = snapshot.revision;
            }
            return true;
        }
    }
    public boolean commitCurrentSnapshot(Owner owner) throws IOException {
        synchronized (coordinator) { return owns(owner) && commitSnapshot(coordinator.latest); }
    }
    public Restored restoreStopped(Owner owner) throws IOException {
        synchronized (coordinator) {
            if (!owns(owner)) throw new IOException("页面恢复所有权已过期");
            return restoreStopped();
        }
    }

    public String claimFileOperation(Owner owner) throws IOException {
        synchronized (coordinator) {
            if (!owns(owner)) throw new IOException("页面文件操作所有权已过期");
            if (coordinator.lease != null) throw new IOException("另一文件选择或读写操作仍在进行，请稍后再试");
            coordinator.lease = UUID.randomUUID().toString(); coordinator.leaseOwner = owner;
            coordinator.providerInFlight = false;
            return coordinator.lease;
        }
    }
    private void requireLease(String lease, boolean requireOwner) throws IOException {
        if (lease == null || !lease.equals(coordinator.lease) || (requireOwner && !owns(coordinator.leaseOwner)))
            throw new IOException("文件操作所有权已过期");
    }
    public void beginProviderIo(String lease) throws IOException {
        synchronized (coordinator) { requireLease(lease, true); coordinator.providerInFlight = true; }
    }
    public void releaseFileOperation(String lease) {
        synchronized (coordinator) { if (lease != null && lease.equals(coordinator.lease)) releaseLease(); }
    }
    private void releaseLease() { coordinator.lease = null; coordinator.leaseOwner = null; coordinator.providerInFlight = false; }

    public void saveSnapshot(Map<String, String> values) throws IOException {
        synchronized (coordinator) {
            if (coordinator.owner != null) throw new IOException("快照写入需要当前页面所有权");
            saveSnapshotValues(values);
        }
    }

    private void saveSnapshotValues(Map<String, String> values) throws IOException {
        if (!SNAPSHOT_KEYS.containsAll(values.keySet())) throw new IllegalArgumentException("恢复快照不能包含登录凭据或未知字段");
        Properties p = new Properties();
        for (String key : SNAPSHOT_KEYS) p.setProperty(key, values.containsKey(key) ? values.get(key) : defaultValue(key));
        save("task-state", p);
    }

    public Map<String, String> loadSnapshot() throws IOException {
        synchronized (coordinator) {
            Properties p = load("task-state");
            Map<String, String> out = new HashMap<>();
            for (String key : SNAPSHOT_KEYS) if (p.containsKey(key)) out.put(key, p.getProperty(key));
            out.put("interrupted", Boolean.toString(Boolean.parseBoolean(out.get("active"))));
            out.put("active", "false"); // Process recreation never replays a task.
            return out;
        }
    }

    public static final class Restored {
        private final Map<String, String> snapshot;
        private final List<String> pendingIds;
        private final List<String> errors;
        private Restored(Map<String, String> snapshot, List<String> pendingIds, List<String> errors) {
            this.snapshot = snapshot; this.pendingIds = pendingIds; this.errors = errors;
        }
        public Map<String, String> getSnapshot() { return new HashMap<>(snapshot); }
        public List<String> getPendingIds() { return new ArrayList<>(pendingIds); }
        public List<String> getErrors() { return new ArrayList<>(errors); }
    }

    /** Independent failure domains: a broken UI snapshot never hides the submitted-ID journal. */
    public Restored restoreStopped() {
        synchronized (coordinator) {
            Map<String, String> snapshot = new HashMap<>();
            List<String> pending = new ArrayList<>(), errors = new ArrayList<>();
            recoveryWarnings.clear();
            try { snapshot = loadSnapshot(); }
            catch (Exception e) { errors.add("恢复快照读取失败，损坏文件已保留：" + e.getMessage()); }
            try { pending = pendingIds(); }
            catch (Exception e) { errors.add("待核验提交记录读取失败，不能假定没有未完成操作：" + e.getMessage()); }
            errors.addAll(recoveryWarnings);
            return new Restored(snapshot, pending, errors);
        }
    }

    public void markSubmitted(String token, List<String> ids) throws IOException {
        synchronized (coordinator) {
            if (!token.matches("[0-9]+")) throw new IllegalArgumentException("无效任务令牌");
            Properties p = load("pending-mutations");
            LinkedHashSet<String> values = split(p.getProperty(token, ""));
            for (String id : ids) {
                if (!id.matches("[0-9]{5,12}")) throw new IllegalArgumentException("无效作品 ID");
                values.add(id);
            }
            p.setProperty(token, join(values));
            save("pending-mutations", p);
        }
    }

    public void markVerified(String token, String id) throws IOException {
        synchronized (coordinator) {
            Properties p = load("pending-mutations");
            LinkedHashSet<String> values = split(p.getProperty(token, ""));
            values.remove(id);
            if (values.isEmpty()) p.remove(token); else p.setProperty(token, join(values));
            save("pending-mutations", p);
        }
    }

    public List<String> pendingIds() throws IOException {
        synchronized (coordinator) {
            Properties p = load("pending-mutations");
            LinkedHashSet<String> out = new LinkedHashSet<>();
            // Properties itself has no ordering guarantee; order each submitted group stably.
            List<String> tokens = new ArrayList<>(p.stringPropertyNames());
            java.util.Collections.sort(tokens);
            for (String token : tokens) out.addAll(split(p.getProperty(token)));
            return new ArrayList<>(out);
        }
    }

    public Export prepareExport(int requestCode, String content) throws IOException {
        synchronized (coordinator) {
            if (coordinator.lease != null) throw new IOException("已有文件操作正在进行");
            return prepareExportValues(requestCode, content);
        }
    }

    public Export prepareOwnedExport(String lease, int requestCode, String content) throws IOException {
        synchronized (coordinator) { requireLease(lease, true); return prepareExportValues(requestCode, content); }
    }

    private Export prepareExportValues(int requestCode, String content) throws IOException {
        if (new File(directory, "pending-export").exists()) throw new IOException("已有文件保存等待完成或取消");
        if (content == null || content.trim().isEmpty()) throw new IOException("没有可保存的内容，未创建空白文件");
        String id = UUID.randomUUID().toString();
        Properties p = new Properties();
        p.setProperty("id", id); p.setProperty("request", Integer.toString(requestCode)); p.setProperty("content", content);
        save("pending-export", p);
        return new Export(id, requestCode);
    }

    public Export pendingExport(String lease, int requestCode) throws IOException {
        synchronized (coordinator) {
            requireLease(lease, true);
            Properties p = load("pending-export");
            if (!Integer.toString(requestCode).equals(p.getProperty("request"))) throw new IOException("没有对应类型的待保存快照");
            return new Export(p.getProperty("id"), requestCode);
        }
    }

    public String readExport(String operationId, int requestCode) throws IOException {
        synchronized (coordinator) {
            if (!new File(directory, "pending-export").exists()) throw new IOException("导出快照不存在，未写入文件");
            Properties p = load("pending-export");
            if (operationId == null || !operationId.equals(p.getProperty("id")) || !Integer.toString(requestCode).equals(p.getProperty("request")))
                throw new IOException("保存操作标识不存在或不匹配，未写入文件");
            return p.getProperty("content");
        }
    }

    public boolean clearExport(String operationId, int requestCode) throws IOException {
        synchronized (coordinator) {
            if (coordinator.owner != null || coordinator.lease != null) throw new IOException("导出清理需要捕获的文件操作所有权");
            return clearExportValues(operationId, requestCode);
        }
    }

    public boolean clearOwnedExport(String lease, String operationId, int requestCode) throws IOException {
        synchronized (coordinator) {
            // Retired waiting owners cannot admit cleanup; already admitted I/O may finish
            // only while its exact lease remains current and blocks any replacement lease.
            requireLease(lease, !coordinator.providerInFlight);
            coordinator.providerInFlight = true;
            return clearExportValues(operationId, requestCode);
        }
    }

    private boolean clearExportValues(String operationId, int requestCode) throws IOException {
        File file = new File(directory, "pending-export");
        if (!file.exists()) return false;
        Properties p = load("pending-export");
        if (operationId == null || !operationId.equals(p.getProperty("id")) || !Integer.toString(requestCode).equals(p.getProperty("request"))) return false;
        if (!file.delete()) throw new IOException("无法清理已完成的保存快照");
        return true;
    }

    public static String readText(InputStream in) throws IOException {
        return new String(readBounded(in, MAX_TXT_BYTES), StandardCharsets.UTF_8);
    }

    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        if (in == null) throw new IOException("无法打开输入文件");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            if (out.size() > maxBytes - count) throw new IOException(maxBytes == MAX_TXT_BYTES ?
                    "TXT 超过 2 MiB，请拆分文件后导入" : "恢复或保存数据过大，请缩小任务");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static String join(Set<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) { if (out.length() > 0) out.append(','); out.append(value); }
        return out.toString();
    }

    private static LinkedHashSet<String> split(String value) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (value != null && !value.isEmpty()) out.addAll(Arrays.asList(value.split(",")));
        return out;
    }

    private static String defaultValue(String key) {
        if (Arrays.asList("private", "remove", "active", "wallpaperOnly").contains(key)) return "false";
        if (Arrays.asList("index", "ok", "converted", "skip", "invalid", "fail", "progress").contains(key)) return "0";
        return "";
    }

    private void validateSchema(String name, Properties p) throws IOException {
        if ("task-state".equals(name)) {
            if (!p.stringPropertyNames().equals(SNAPSHOT_KEYS)) throw new IOException("恢复快照字段不完整或版本不匹配");
            for (String key : Arrays.asList("private", "remove", "active", "wallpaperOnly"))
                if (!Arrays.asList("true", "false").contains(p.getProperty(key))) throw new IOException("恢复快照布尔字段无效");
            for (String key : Arrays.asList("index", "ok", "converted", "skip", "invalid", "fail", "progress")) {
                try { if (Integer.parseInt(p.getProperty(key)) < 0) throw new NumberFormatException(); }
                catch (NumberFormatException e) { throw new IOException("恢复快照计数无效", e); }
            }
            if (Integer.parseInt(p.getProperty("progress")) > 100) throw new IOException("恢复快照进度无效");
        } else if ("pending-export".equals(name)) {
            if (!p.stringPropertyNames().equals(new LinkedHashSet<>(Arrays.asList("id", "request", "content"))) ||
                    !p.getProperty("id", "").matches("[a-f0-9-]{36}") || !p.getProperty("request", "").matches("100[23]") || p.getProperty("content", "").trim().isEmpty())
                throw new IOException("导出快照字段不完整或内容为空");
        } else if ("pending-mutations".equals(name)) {
            for (String token : p.stringPropertyNames()) {
                if (!token.matches("[0-9]+") || !p.getProperty(token).matches("[0-9]{5,12}(,[0-9]{5,12})*"))
                    throw new IOException("待核验记录字段无效");
            }
        } else throw new IOException("未知恢复文件类型");
    }

    private byte[] bytes(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return readBounded(in, MAX_STATE_BYTES); }
    }

    private static byte[] digest(byte[] body) throws IOException {
        try { return MessageDigest.getInstance("SHA-256").digest(body); }
        catch (NoSuchAlgorithmException e) { throw new IOException("无法验证恢复文件摘要", e); }
    }

    private Properties decode(String name, byte[] frame) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(frame))) {
            if (in.readInt() != ENVELOPE_V1 || !name.equals(in.readUTF())) throw new IOException("恢复文件版本或类型不匹配");
            int length = in.readInt();
            byte[] expected = new byte[32]; in.readFully(expected);
            if (length < 0 || length != in.available()) throw new IOException("恢复文件长度不完整");
            byte[] body = new byte[length]; in.readFully(body);
            if (!MessageDigest.isEqual(expected, digest(body))) throw new IOException("恢复文件摘要不匹配");
            Properties p = new Properties();
            try { p.load(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException e) { throw new IOException("恢复文件内容损坏", e); }
            validateSchema(name, p);
            return p;
        }
    }

    private void quarantine(File file) throws IOException {
        if (!file.exists()) return;
        File invalid = new File(directory, file.getName() + ".invalid-" + System.nanoTime());
        if (!file.renameTo(invalid)) throw new IOException("无法保留损坏恢复文件，未覆盖原文件");
    }

    private Properties load(String name) throws IOException {
        File file = new File(directory, name);
        if (!file.exists()) {
            File backup = new File(directory, name + ".bak");
            if ("task-state".equals(name) && backup.exists()) {
                final byte[] previous;
                final Properties verified;
                try {
                    previous = bytes(backup);
                    verified = decode(name, previous);
                } catch (IOException invalidBackup) {
                    quarantine(backup);
                    throw invalidBackup;
                }
                // Keep a verified backup intact if rewriting the missing primary fails.
                writeAtomic(file, previous);
                recoveryWarnings.add("恢复快照主文件缺失，已恢复已验证的备份；待核验记录独立读取");
                return verified;
            }
            return new Properties();
        }
        try { return decode(name, bytes(file)); }
        catch (IOException invalid) {
            if ("task-state".equals(name)) {
                File backup = new File(directory, name + ".bak");
                try {
                    byte[] previous = bytes(backup);
                    Properties verified = decode(name, previous);
                    quarantine(file);
                    writeAtomic(file, previous);
                    recoveryWarnings.add("恢复快照损坏，已保留损坏文件并恢复已验证的前次快照；待核验记录独立读取");
                    return verified;
                } catch (IOException noVerifiedBackup) {
                    quarantine(file); // Preserve evidence before any stopped/default state is saved.
                }
            } else if ("pending-export".equals(name)) {
                quarantine(file); // Never substitute an older same-request-code export.
            }
            // A corrupt mutation journal stays in place, denying later mutation admission.
            throw invalid;
        }
    }

    private void writeAtomic(File target, byte[] frame) throws IOException {
        File temporary = File.createTempFile(target.getName() + "-", ".tmp", directory);
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(frame); out.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("无法原子保存恢复数据");
        }
    }

    private void save(String name, Properties p) throws IOException {
        validateSchema(name, p);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        OutputStreamWriter writer = new OutputStreamWriter(body, StandardCharsets.UTF_8);
        p.store(writer, null); writer.flush();
        byte[] payload = body.toByteArray();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(encoded)) {
            out.writeInt(ENVELOPE_V1); out.writeUTF(name); out.writeInt(payload.length);
            out.write(digest(payload)); out.write(payload);
        }
        byte[] frame = encoded.toByteArray();
        if (frame.length > MAX_STATE_BYTES) throw new IOException("恢复或保存数据超过 8 MiB，请缩小任务");
        File target = new File(directory, name);
        if ("task-state".equals(name)) {
            byte[] previous = frame;
            if (target.exists()) {
                try { previous = bytes(target); decode(name, previous); }
                catch (IOException invalid) { quarantine(target); previous = frame; }
            }
            // Only UI state may fall back to an older snapshot; journal/export never do.
            writeAtomic(new File(directory, name + ".bak"), previous);
        }
        writeAtomic(target, frame);
    }
}
