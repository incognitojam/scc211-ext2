package com.gitlab.incognitojam.ext2;

import java.io.*;
import java.nio.ByteBuffer;

public class Volume implements Closeable {
    private final RandomAccessFile fsFile;
    private Superblock superblock;
    private BlockGroupDescriptor[] blockGroupDescriptors;

    public Volume(String filepath) throws IOException {
        this.fsFile = new RandomAccessFile(filepath, "r");
        setupSuperblock();
        setupBlockGroupDescriptorTable();
    }

    private void setupSuperblock() throws IOException {
        // Superblock starts at byte 1024.
        fsFile.seek(1024);

        // Read the next 1024 bytes.
        byte[] superblockBytes = new byte[1024];
        fsFile.readFully(superblockBytes, 0, 1024);

        // Create a Superblock object from these bytes.
        superblock = new Superblock(ByteBuffer.wrap(superblockBytes));
    }

    private void setupBlockGroupDescriptorTable() {
        // Read the BGD table from disk.
        // FIXME: the index might be different for other block sizes
        ByteBuffer buffer = readBlock(2);

        // number of groups = number of blocks / number of blocks per group
        final int blockGroupCount =
                (int) Math.ceil((double) superblock.getBlocksCount() / (double) superblock.getBlocksInGroup());

        // Construct the BGDs.
        blockGroupDescriptors = new BlockGroupDescriptor[blockGroupCount];
        for (int i = 0; i < blockGroupCount; i++)
            /*
             * We pass the buffer to the BGD constructor as-is, since each BGD
             * immediately follows the previous, so the buffer pointer will
             * already be in the correct position.
             */
            blockGroupDescriptors[i] = new BlockGroupDescriptor(buffer);
    }

    /**
     * Read a block of data from the disk.
     * <p>
     * {@link Superblock} <b>must</b> be read before use, as the block size
     * value is required to calculate the block offset.
     * <p>
     * The bytes are read from the starting offset of the block, and includes
     * the entire range of the block (see {@link Superblock#getFsBlockSize}).
     *
     * @param index the block index
     * @return Returns a ByteBuffer backed by an array of bytes read from the
     * disk.
     */
    private ByteBuffer readBlock(int index) {
        // Location of the block from the start of the disk.
        final int offset = index * superblock.getFsBlockSize();

        // Read the bytes from disk.
        final byte[] bytes = readRange(offset, superblock.getFsBlockSize());

        return ByteBuffer.wrap(bytes);
    }

    /**
     * Read an array of bytes from disk given an offset and a length.
     *
     * @param offset the offset from the beginning of the disk to read from
     * @param length the number of bytes to read
     * @return Returns a byte array containing the bytes read from the disk.
     */
    private byte[] readRange(int offset, int length) {
        /*
         * Create a backing array for the data, size determined from the given
         * length argument.
         */
        byte[] bytes = new byte[length];

        try {
            // Seek to offset.
            fsFile.seek(offset);

            // Read the data into the array.
            fsFile.readFully(bytes);
        } catch (IOException ignored) {
        }

        return bytes;
    }

    @Override
    public void close() throws IOException {
        fsFile.close();
    }

    public String getLabel() {
        return superblock.getVolumeLabel();
    }

    public int getBlocks() {
        return superblock.getBlocksCount();
    }

    public int getBlockSize() {
        return superblock.getFsBlockSize();
    }

    public int getCapacity() {
        return getBlocks() * getBlockSize();
    }
}
