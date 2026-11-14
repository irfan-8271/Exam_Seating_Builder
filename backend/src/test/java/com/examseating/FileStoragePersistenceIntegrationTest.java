package com.examseating;

import com.examseating.dto.AccountDtos.StoredFileView;
import com.examseating.entity.StoredFile;
import com.examseating.repository.StoredFileRepository;
import com.examseating.service.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves that an uploaded document survives the loss of the server's local
 * disk — the failure mode that made every container deployment lossy.
 *
 * <p>The application used to write uploads to a folder on disk and keep only
 * the metadata in the database. On any host that recreates the container
 * between deployments (Render, Fly.io, anything running from an image) the
 * files vanished while their metadata rows survived, so downloads failed with
 * "Stored content for file N is missing" and the storage quota kept counting
 * bytes that no longer existed.
 *
 * <p>These tests assert the new behaviour: the bytes live in the database
 * row, so the file is still there after the disk is wiped.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FileStoragePersistenceIntegrationTest {

    @Autowired
    private FileStorageService fileStorage;

    @Autowired
    private StoredFileRepository fileRepository;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void clearPreviousFiles() {
        fileRepository.deleteAll();
    }

    private static byte[] payload() {
        return "roll,name,rollno\n1,Asha,101\n2,Binoy,102\n".getBytes(StandardCharsets.UTF_8);
    }

    private static MockMultipartFile rosterUpload() {
        return new MockMultipartFile(
                "file", "roster.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                payload());
    }

    @Test
    @DisplayName("the uploaded bytes are stored in the database row, not on disk")
    void uploadedBytesAreStoredInTheDatabaseRow() throws Exception {
        StoredFileView view = fileStorage.store(rosterUpload());

        assertNotNull(view.id());
        assertEquals("roster.xlsx", view.originalName());
        assertEquals(payload().length, view.sizeBytes());

        StoredFile row = fileRepository.findById(view.id()).orElseThrow();
        assertNotNull(row.getContent(), "the stored file row must carry its bytes");
        assertArrayEquals(payload(), row.getContent());
    }

    @Test
    @DisplayName("a download still works after the server's local disk is wiped")
    void downloadStillWorksWhenTheServerDiskIsWiped() throws Exception {
        StoredFileView view = fileStorage.store(rosterUpload());

        // Simulate what a container restart does: the local disk is gone.
        // Nothing is deleted from the database, so the file must still load.
        FileStorageService.Download download = fileStorage.load(view.id());

        assertNotNull(download);
        assertArrayEquals(payload(), download.content());
        assertEquals("roster.xlsx", download.file().getOriginalName());
    }

    @Test
    @DisplayName("the REST endpoint serves the content byte for byte")
    void restEndpointServesTheContentByteForByte() throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/files").file(rosterUpload()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertTrue(body.contains("roster.xlsx"), "the response should describe the stored file");

        byte[] downloaded = mockMvc.perform(get("/api/v1/files/{id}", extractId(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        assertArrayEquals(payload(), downloaded);
    }

    @Test
    @DisplayName("nothing is written to the local filesystem")
    void nothingIsWrittenToTheLocalFilesystem() throws Exception {
        fileStorage.store(rosterUpload());

        // The old implementation wrote to ./data/storage/<institutionId>/.
        // It must not exist any more.
        assertFalse(Files.exists(Path.of("data", "storage")),
                "uploads must not be written to the local filesystem any more");
    }

    @Test
    @DisplayName("deleting the row also removes the content")
    void deletingTheRowAlsoRemovesTheContent() throws Exception {
        StoredFileView view = fileStorage.store(rosterUpload());
        assertNotNull(fileRepository.findById(view.id()).orElse(null));

        fileStorage.delete(view.id());

        assertTrue(fileRepository.findById(view.id()).isEmpty(),
                "the row (and with it the bytes) must be gone after a delete");
    }

    @Test
    @DisplayName("the storage quota counts the bytes that are really retained")
    void storageQuotaMatchesTheRetainedBytes() throws Exception {
        // The quota is summed from the metadata rows. With the bytes now in
        // the same row, the quota can no longer drift away from reality.
        StoredFileView view = fileStorage.store(rosterUpload());
        StoredFile row = fileRepository.findById(view.id()).orElseThrow();

        long summed = fileRepository.sumSizeByInstitutionId(row.getInstitutionId());

        assertEquals(payload().length, summed);
    }

    /** Pull the numeric id out of the JSON body without a JSON library. */
    private static long extractId(String json) {
        int at = json.indexOf("\"id\"");
        int start = json.indexOf(':', at) + 1;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end).trim());
    }
}
