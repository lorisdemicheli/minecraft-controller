//package it.lorisdemicheli.server_controller.resource;
//
//import jakarta.ws.rs.Path;
//
//@Path("/servers/{serverName}/files")
//public class ServerFileResource {
//
//    @Autowired
//    private KubernetesServerInstanceService service;
//
//    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
//    public ResponseEntity<List<FileEntry>> listFiles(
//            @PathVariable String serverName,
//            @RequestParam(defaultValue = "/") String path) {
//        return ResponseEntity.ok(service.listFiles(serverName, path));
//    }
//
//    @GetMapping(value = "/content", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
//    public ResponseEntity<StreamingResponseBody> downloadFile(
//            @PathVariable String serverName,
//            @RequestParam String path) {
//        
//        String filename = path.contains("/") ? path.substring(path.lastIndexOf("/") + 1) : path;
//        
//        return ResponseEntity.ok()
//                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
//                .contentType(MediaType.APPLICATION_OCTET_STREAM)
//                .body(out -> service.downloadFile(serverName, path, out));
//    }
//
//    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
//    public ResponseEntity<Void> uploadFile(
//            @PathVariable String serverName,
//            @RequestParam MultipartFile file,
//            @RequestParam String destPath) {
//        service.uploadFile(serverName, destPath, file.getResource());
//        return ResponseEntity.noContent().build();
//    }
//
//    @PostMapping(value = "/directory")
//    public ResponseEntity<Void> createDirectory(
//            @PathVariable String serverName, 
//            @RequestParam String path) {
//        service.createDirectory(serverName, path);
//        return ResponseEntity.noContent().build();
//    }
//
//    @PostMapping(value = "/touch")
//    public ResponseEntity<Void> createEmptyFile(
//            @PathVariable String serverName, 
//            @RequestParam String path) {
//        service.createEmptyFile(serverName, path);
//        return ResponseEntity.noContent().build();
//    }
//
//    @DeleteMapping
//    public ResponseEntity<Void> deletePath(
//            @PathVariable String serverName, 
//            @RequestParam String path) {
//        service.deletePath(serverName, path);
//        return ResponseEntity.noContent().build();
//    }