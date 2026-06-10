package io.conddo.api.publicapi;

import io.conddo.core.auth.CustomerJwtService;
import io.conddo.core.service.MediaService;
import io.conddo.core.service.MediaService.MediaView;
import io.conddo.core.storage.StorageException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Public-side file upload (PHARMACY_PUBLIC_API_SPEC §10). The
 * customer uploads a prescription image (or any small file) and gets
 * back a permanent CDN URL they can pass to
 * {@code POST /pharmacy/prescriptions} as {@code fileUrl}.
 *
 * <p>Site-key auth (PublicSiteInterceptor) binds the tenant; the
 * customer JWT is verified inline so {@code uploadedBy} on the
 * resulting {@link io.conddo.core.domain.MediaAsset} carries the
 * customer's id. Storage cap + content-type validation are the
 * same code path as the dashboard upload — MediaService is the
 * single recorder.
 */
@RestController
@RequestMapping("/api/v1/public/{slug}")
public class PublicUploadController {

    private final MediaService mediaService;
    private final CustomerJwtService customerJwtService;

    public PublicUploadController(MediaService mediaService,
                                  CustomerJwtService customerJwtService) {
        this.mediaService = mediaService;
        this.customerJwtService = customerJwtService;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                      @RequestParam(required = false) String kind,
                                                      HttpServletRequest request) {
        UUID customerId = PublicCustomerAuth.requireCustomerId(request, customerJwtService);
        try {
            MediaView view = mediaService.upload(file.getOriginalFilename(), file.getContentType(),
                    file.getSize(), file.getInputStream(),
                    kind == null || kind.isBlank() ? "prescription" : kind,
                    null, null, customerId);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("fileUrl", view.url());
            body.put("id", view.id());
            body.put("contentType", view.contentType());
            body.put("size", view.size());
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        } catch (IOException ex) {
            throw new StorageException("Could not read the uploaded file", ex);
        }
    }
}
