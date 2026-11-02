package com.acme.coderag.indexer.git;

/**
 * One blob listed by {@code git ls-tree}.
 *
 * @param path     repository-relative path
 * @param blobSha  git object id. Compared against {@code code_file.blob_sha} to
 *                 decide whether the file needs any work at all -- this is the
 *                 cheapest of the three deduplication levels and the one that
 *                 eliminates the vast majority of files on a typical commit.
 * @param sizeBytes blob size, used to skip generated or vendored monsters
 */
public record TreeEntry(String path, String blobSha, long sizeBytes) {
}
