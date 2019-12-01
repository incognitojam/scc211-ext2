package com.gitlab.incognitojam.ext2;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Inodes hold information about the files and directories held within the
 * filesystem. They identify who should have access to the data, timestamps
 * giving update information, and links to all the data blocks that form each
 * file.
 */
public class Inode {
    /**
     * The contents of the file mode field will be some combination of the following
     * values, i.e. the values ORed together. These bits are used to identify the
     * type of file references by the inode and file permissions, the rwxr-xr-x seen
     * in the Unix directory listings.
     */
    public static class FileModes {
        /**
         * Socket
         */
        public static final int IFSCK = 0xC000;
        /**
         * Symbolic Link
         */
        public static final int IFLNK = 0xA000;
        /**
         * Regular File
         */
        public static final int IFREG = 0x8000;
        /**
         * Block Device
         */
        public static final int IFBLK = 0x6000;
        /**
         * Directory
         */
        public static final int IFDIR = 0x4000;
        /**
         * Character Device
         */
        public static final int IFCHR = 0x2000;
        /**
         * FIFO
         */
        public static final int IFIFO = 0x1000;

        /**
         * Set process User ID
         */
        public static final int ISUID = 0x0800;
        /**
         * Set process Group ID
         */
        public static final int ISGID = 0x0400;
        /**
         * Sticky bit
         */
        public static final int ISVTX = 0x0200;

        /**
         * User read
         */
        public static final int IRUSR = 0x0100;
        /**
         * User write
         */
        public static final int IWUSR = 0x0080;
        /**
         * User execute
         */
        public static final int IXUSR = 0x0040;

        /**
         * Group read
         */
        public static final int IRGRP = 0x0020;
        /**
         * Group write
         */
        public static final int IWGRP = 0x0010;
        /**
         * Group execute
         */
        public static final int IXGRP = 0x0008;

        /**
         * Others read
         */
        public static final int IROTH = 0x0004;
        /**
         * Others write
         */
        public static final int IWOTH = 0x0002;
        /**
         * Others execute
         */
        public static final int IXOTH = 0x0001;

        /**
         * Convert a filemode value to it's string representation in unix.
         *
         * @param filemode the filemode value to parse
         * @return returns ASCII representation of the filemode value
         * @see <a href="https://github.com/coreutils/gnulib/blob/master/lib/filemode.c#L96">strmode in glibc.</a>
         */
        public static String toString(final int filemode) {
            final char[] str = new char[10];

            // file type
            str[0] = parseFileType(filemode);

            // user permissions
            str[1] = (filemode & IRUSR) == IRUSR ? 'r' : '-';
            str[2] = (filemode & IWUSR) == IWUSR ? 'w' : '-';
            str[3] = (filemode & IXUSR) == IXUSR ? 'x' : '-';

            // group permissions
            str[4] = (filemode & IRGRP) == IRGRP ? 'r' : '-';
            str[5] = (filemode & IWGRP) == IWGRP ? 'w' : '-';
            str[6] = (filemode & IXGRP) == IXGRP ? 'x' : '-';

            // others permissions
            str[7] = (filemode & IROTH) == IROTH ? 'r' : '-';
            str[8] = (filemode & IWOTH) == IWOTH ? 'w' : '-';
            str[9] = (filemode & IXOTH) == IXOTH ? 'x' : '-';

            return new String(str);
        }

        /**
         * Get a character indicating the type of file descriped by the
         * filemode bits.
         *
         * @param filemode the filemode to parse
         * @return returns the character matching the file type
         */
        private static char parseFileType(final int filemode) {
            if (testBitmask(filemode, IFREG))
                return '-';
            if (testBitmask(filemode, IFDIR))
                return 'd';
            if (testBitmask(filemode, IFBLK))
                return 'b';
            if (testBitmask(filemode, IFCHR))
                return 'c';
            if (testBitmask(filemode, IFLNK))
                return 'l';
            if (testBitmask(filemode, IFIFO))
                return 'p';
            if (testBitmask(filemode, IFSCK))
                return 's';

            // none of the tests matched, we don't know what the type is
            return '?';
        }

        public static boolean testBitmask(final int bits, final int mask) {
            return (bits & mask) == mask;
        }
    }

    private final Volume volume;
    private final short fileMode;
    private final short userId;
    private final long lastAccessTime;
    private final long creationTime;
    private final long lastModifiedTime;
    private final long deletedTime;
    private final short groupId;
    private final short hardLinksCount;
    private final int[] directPtrs;
    private final int indirectPtr;
    private final int doubleIndirectPtr;
    private final int tripleIndirectPtr;
    private final long fileSize;

    /**
     * Construct an Inode by reading data from bytes.
     *
     * @param bytes the bytes to read attributes from
     */
    Inode(Volume volume, byte[] bytes) {
        this.volume = volume;
        ByteBuffer buffer = ByteUtils.wrap(bytes);

        fileMode = buffer.getShort(0);
        userId = buffer.getShort(2);
        int fileSizeLower = buffer.getInt(4);
        lastAccessTime = buffer.getInt(8);
        creationTime = buffer.getInt(12);
        lastModifiedTime = buffer.getInt(16);
        deletedTime = buffer.getInt(20);
        groupId = buffer.getShort(24);
        hardLinksCount = buffer.getShort(26);

        // Read block pointers.
        directPtrs = new int[12];
        for (int i = 0; i < 12; i++)
            directPtrs[i] = buffer.getInt(40 + i * 4);

        indirectPtr = buffer.getInt(88);
        doubleIndirectPtr = buffer.getInt(92);
        tripleIndirectPtr = buffer.getInt(96);

        int fileSizeUpper = buffer.getInt(104);
        fileSize = ((long) fileSizeUpper << 32) | (fileSizeLower & 0xFFFFFFFFL);
    }

    /**
     * TODO(docs): write javadoc
     */
    byte[] read(long startOffset, int length) {
        /*
         * Since it is not possible to read beyond the end of the file,
         * transform the request to reduce the length of bytes read.
         */
        if (startOffset + length > getFileSize())
            length = (int) (getFileSize() - startOffset);

        /*
         * Calculate the local block number and starting byte for the data we
         * we want to read inside this file.
         *
         * For example, to read bytes [0, 32) we need local block 0 starting at
         * local byte 0. To read bytes [1500, 1600) we need local block 1
         * starting at local byte 476.
         */
        // first inode data block to read from
        final int localBlockStartNumber = (int) Long.divideUnsigned(startOffset, volume.getBlockSize());

        // starting position in first block
        final int localBlockStartOffset = (int) Long.remainderUnsigned(startOffset, volume.getBlockSize());

        byte[] dst = new byte[length];
        int bytesRead = 0;
        int localBlockNumber = localBlockStartNumber;
        while (bytesRead < length) {
            /*
             * The offset to read from in this block should be "local block
             * start offset" if it's the first block we are reading, otherwise
             * read from the start of the block.
             */
            final int localOffset = bytesRead == 0 ? localBlockStartOffset : 0;

            /*
             * The length of data to read is either from the local offset to
             * the end of the block, or the total number of bytes read so far
             * minus the total length to read, whichever is smaller.
             */
            final int localLength = Math.min(volume.getBlockSize() - localOffset, length - bytesRead);

            final int dataBlock = traverseDataPtrs(localBlockNumber);
            if (dataBlock == 0) {
                /*
                 * We are attempting to read a hole in the file, so fill with
                 * zero bytes.
                 */
                Arrays.fill(dst, bytesRead, bytesRead + localLength, (byte) 0);
            } else {
                volume.seek(dataBlock * volume.getBlockSize() + localOffset);
                volume.read(dst, bytesRead, localLength);
            }

            /*
             * Count up the total number of bytes read and increment the local
             * block pointer.
             */
            bytesRead += localLength;
            localBlockNumber++;
        }

        return dst;
    }

    /**
     * TODO(docs): write javadoc
     */
    DirectoryEntry[] getEntries() {
        if (!FileModes.testBitmask(getFileMode(), FileModes.IFDIR))
            throw new UnsupportedOperationException("Must only call Inode#getEntries() on directories.");

        final int size = (int) getFileSize();
        byte[] dataBytes = read(0, size);
        ByteBuffer buffer = ByteUtils.wrap(dataBytes);

        List<DirectoryEntry> entries = new ArrayList<>();
        int ptr = 0;
        while (ptr < size) {
            buffer.position(ptr);
            DirectoryEntry entry = new DirectoryEntry(buffer);

            // Skip entries which don't point to a valid inode
            if (entry.getInode() > 0)
                entries.add(entry);

            ptr += entry.getLength();
        }

        return entries.toArray(new DirectoryEntry[entries.size()]);
    }

    /**
     * TODO(docs): write javadoc
     */
    private static int readPtr(Volume volume) {
        byte[] ptrData = new byte[4];
        volume.read(ptrData);
        return ByteUtils.wrap(ptrData).getInt();
    }

    /**
     * Retrieve a data block pointer for this inode by it's local index.
     * <p>
     * The block pointer to data block 0 can be retrieved from the {@link Inode#directPtrs}
     * array at index 0, while data block 11 is found in the {@link Inode#directPtrs}
     * array at index 11. Higher indices are referenced indirectly, such as
     * data block 12 which is located in the first indirect data block at
     * position 0.
     * <p>
     * Which each added level of indirection, more data blocks must be read
     * at a particular offset to retrieve the real data block pointer. This
     * method is used to perform this traversal of the tree.
     * <p>
     * This method supports up to three levels of indirection, or triple
     * indirect pointers.
     *
     * @param localBlockNumber the data block to read in this inode, in the
     *                         range <pre>0 <= localBlockNumber < 16843020</pre>
     *                         for filesystems with 1K block size.
     * @return Returns the real pointer to the data block on disk.
     */
    private int traverseDataPtrs(int localBlockNumber) {
        /*
         * If the requested local block number is less than 12, this means the
         * data is held in one of the first direct pointers.
         */
        if (localBlockNumber < 12)
            return directPtrs[localBlockNumber];
        localBlockNumber -= 12;

        /*
         * If the localBlockNumber is in 0 <= x < 256, then the data we need is
         * in a block referenced by the indirect block data.
         *
         * Navigate to indirectPtr + localBlockNumber * sizeof(int) and read
         * the int to get the ptr to the data block.
         */
        final int ptrsPerBlock = volume.getBlockSize() / 4;
        if (localBlockNumber < ptrsPerBlock) {
            if (indirectPtr == 0) return 0;

            volume.seek(indirectPtr * volume.getBlockSize() + localBlockNumber * 4);
            return readPtr(volume);
        }
        localBlockNumber -= ptrsPerBlock;

        /*
         * If the localBlockNumber is in 0 <= x < 65536, then the data we need
         * is in a block referenced by the double indirect block data.
         *
         * Navigate to doubleIndirectPtr + (localBlockNumber / 256) * sizeof(int)
         * and read the int to get the ptr to the indirect data block.
         *
         * Now navigate to indirectPtr + (localBlockNumber % 256) * sizeof(int)
         * and read the int to get the ptr to the data block.
         */
        if (localBlockNumber < ptrsPerBlock * ptrsPerBlock) {
            if (doubleIndirectPtr == 0) return 0;

            volume.seek(doubleIndirectPtr * volume.getBlockSize() + (localBlockNumber / 256) * 4);
            int indirectPtr = readPtr(volume);
            if (indirectPtr == 0) return 0;

            volume.seek(indirectPtr * volume.getBlockSize() + (localBlockNumber % 256) * 4);
            return readPtr(volume);
        }
        localBlockNumber -= ptrsPerBlock * ptrsPerBlock;

        /*
         * If the localBlockNumber is in 0 <= x < 16777216, then the data we
         * need is in a block referenced by the triple indirect block data.
         *
         * Navigate to tripleIndirectPtr + (localBlockNumber / 65536) * sizeof(int)
         * and read the int to get the ptr to the double indirect data block.
         *
         * Now navigate to doubleIndirectPtr + ((localBlockNumber % 65536) / 256) * sizeof(int)
         * and read the int to get the ptr to the indirect data block.
         *
         * Now navigate to indirectPtr + (localBlockNumber % 256) * sizeof(int) and read
         * the int to get the ptr to the data block.
         */
        if (localBlockNumber < ptrsPerBlock * ptrsPerBlock * ptrsPerBlock) {
            if (tripleIndirectPtr == 0) return 0;

            volume.seek(tripleIndirectPtr * volume.getBlockSize() + (localBlockNumber / 65536) * 4);
            int doubleIndirectPtr = readPtr(volume);
            if (doubleIndirectPtr == 0) return 0;

            volume.seek(doubleIndirectPtr * volume.getBlockSize() + ((localBlockNumber % 65536) / 256) * 4);
            int indirectPtr = readPtr(volume);
            if (indirectPtr == 0) return 0;

            volume.seek(indirectPtr * volume.getBlockSize() + (localBlockNumber % 256) * 4);
            return readPtr(volume);
        }

        // if we get here, something has gone wrong
        throw new IllegalStateException("invalid inode data ptr");
    }

    /**
     * Determines the file type and how the file's owner, it's group and others can
     * access the file.
     * <p>
     * See {@link FileModes} for values.
     */
    public short getFileMode() {
        return fileMode;
    }

    /**
     * Records the user ID of the owner of the file.
     */
    public short getUserId() {
        return userId;
    }

    /**
     * The last time this inode was accessed.
     */
    public long getLastAccessTime() {
        return lastAccessTime;
    }

    /**
     * The time when this inode was created.
     */
    public long getCreationTime() {
        return creationTime;
    }

    /**
     * The last time this inode was modified.
     */
    public long getLastModifiedTime() {
        return lastModifiedTime;
    }

    /**
     * The time this inode was deleted.
     */
    public long getDeletedTime() {
        return deletedTime;
    }

    /**
     * Records the group ID of the owner of the file.
     */
    public short getGroupId() {
        return groupId;
    }

    /**
     * Count of how many times this particular node is linked to.
     * <p>
     * Most files have a link count of one. Files with hard links pointing to
     * them will hae an additional count for each hard link.
     * <p>
     * When the link count reaches zero, the inode and all of its associated
     * blocks are freed.
     */
    public short getHardLinksCount() {
        return hardLinksCount;
    }

    /**
     * The size of this file (if it is a regular file, or a symbolic link) in
     * bytes.
     */
    public long getFileSize() {
        return fileSize;
    }
}
