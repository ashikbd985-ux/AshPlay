
<?php
// AshPlay API: Video Catalog + Online Viewer Count + Thumbnail Cache

$root = realpath(__DIR__);
$allowed = array('mp4', 'm4v', 'mkv', 'webm', 'avi', 'mov', '3gp', 'mpeg', 'mpg');

// Online viewer data
$presenceDir = rtrim(sys_get_temp_dir(), DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR . 'ashplay_presence';

if (!is_dir($presenceDir)) {
    @mkdir($presenceDir, 0775, true);
}

// Thumbnail cache folder
$thumbDir = __DIR__ . DIRECTORY_SEPARATOR . 'thumb-cache';

function jsonResponse($data, $status = 200) {
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
    echo json_encode($data, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
    exit;
}

function cleanSessionId($value) {
    return preg_match('/^[a-fA-F0-9-]{16,64}$/', (string)$value) ? (string)$value : '';
}

function activeViewerCount($dir) {
    $count = 0;
    $now = time();

    foreach (glob($dir . DIRECTORY_SEPARATOR . '*.json') ?: array() as $file) {
        $raw = @file_get_contents($file);
        $data = json_decode($raw, true);

        if (!is_array($data) || empty($data['viewing']) || !isset($data['last_seen'])) {
            @unlink($file);
            continue;
        }

        if (($now - (int)$data['last_seen']) <= 45) {
            $count++;
        } else {
            @unlink($file);
        }
    }

    return $count;
}

// Validate video path
function resolveVideoPath($root, $relative, $allowed) {
    $relative = str_replace('\\', '/', (string)$relative);

    if ($relative === '' || strpos($relative, "\0") !== false || $relative[0] === '/') {
        return false;
    }

    $parts = explode('/', $relative);

    foreach ($parts as $part) {
        if ($part === '' || $part === '.' || $part === '..') {
            return false;
        }
    }

    $candidate = realpath($root . DIRECTORY_SEPARATOR . str_replace('/', DIRECTORY_SEPARATOR, $relative));

    if ($candidate === false || !is_file($candidate)) {
        return false;
    }

    $prefix = rtrim($root, DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR;

    if (strpos($candidate, $prefix) !== 0) {
        return false;
    }

    $extension = strtolower(pathinfo($candidate, PATHINFO_EXTENSION));

    if (!in_array($extension, $allowed, true)) {
        return false;
    }

    return $candidate;
}

// Unique thumbnail name for each video version
function thumbnailFileName($relative, $videoFile) {
    $signature = (string)@filesize($videoFile) . ':' . (string)@filemtime($videoFile);

    return hash('sha256', str_replace('\\', '/', $relative) . ':' . $signature) . '.jpg';
}

// Thumbnail URL
function thumbnailUrl($relative) {
    $scheme = (
        !empty($_SERVER['HTTPS']) &&
        $_SERVER['HTTPS'] !== 'off'
    ) ? 'https' : 'http';

    $host = isset($_SERVER['HTTP_HOST']) ? $_SERVER['HTTP_HOST'] : 'localhost';
    $script = isset($_SERVER['SCRIPT_NAME']) ? $_SERVER['SCRIPT_NAME'] : '/ashplay-api.php';

    return $scheme . '://' . $host . $script . '?action=thumbnail&path=' . rawurlencode($relative);
}

$action = isset($_GET['action']) ? (string)$_GET['action'] : '';


// ==========================================
// 1. DISPLAY CACHED THUMBNAIL
// ==========================================

if ($action === 'thumbnail') {

    $relative = isset($_GET['path']) ? $_GET['path'] : '';

    $videoFile = resolveVideoPath($root, $relative, $allowed);

    if ($videoFile === false) {
        http_response_code(404);
        exit;
    }

    $thumbFile = $thumbDir . DIRECTORY_SEPARATOR . thumbnailFileName($relative, $videoFile);

    if (!is_file($thumbFile)) {
        http_response_code(404);
        header('Cache-Control: no-store');
        exit;
    }

    header('Content-Type: image/jpeg');
    header('Content-Length: ' . filesize($thumbFile));
    header('Cache-Control: public, max-age=31536000, immutable');
    header('X-Content-Type-Options: nosniff');

    readfile($thumbFile);
    exit;
}


// ==========================================
// 2. UPLOAD THUMBNAIL FROM ANDROID APP
// ==========================================

if ($action === 'thumbnail_upload') {

    if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
        jsonResponse(array(
            'success' => false,
            'error' => 'POST required'
        ), 405);
    }

    $relative = isset($_POST['path']) ? $_POST['path'] : '';

    $videoFile = resolveVideoPath($root, $relative, $allowed);

    if ($videoFile === false) {
        jsonResponse(array(
            'success' => false,
            'error' => 'Invalid video path'
        ), 400);
    }

    if (!isset($_FILES['thumbnail']) ||
        !is_uploaded_file($_FILES['thumbnail']['tmp_name'])) {

        jsonResponse(array(
            'success' => false,
            'error' => 'Thumbnail file missing'
        ), 400);
    }

    if ($_FILES['thumbnail']['error'] !== UPLOAD_ERR_OK) {
        jsonResponse(array(
            'success' => false,
            'error' => 'Thumbnail upload failed'
        ), 400);
    }

    $imageInfo = @getimagesize($_FILES['thumbnail']['tmp_name']);

    if ($imageInfo === false ||
        !isset($imageInfo['mime']) ||
        $imageInfo['mime'] !== 'image/jpeg') {

        jsonResponse(array(
            'success' => false,
            'error' => 'Only JPEG thumbnails are accepted'
        ), 400);
    }

    if ((int)$_FILES['thumbnail']['size'] > 2 * 1024 * 1024) {
        jsonResponse(array(
            'success' => false,
            'error' => 'Thumbnail is too large'
        ), 413);
    }

    if (!is_dir($thumbDir) && !@mkdir($thumbDir, 0775, true)) {
        jsonResponse(array(
            'success' => false,
            'error' => 'Cannot create thumb-cache folder'
        ), 500);
    }

    $thumbFile = $thumbDir . DIRECTORY_SEPARATOR . thumbnailFileName($relative, $videoFile);

    // Already cached: do not upload again
    if (is_file($thumbFile) && filesize($thumbFile) > 0) {
        jsonResponse(array(
            'success' => true,
            'cached' => true,
            'thumbnail_url' => thumbnailUrl($relative)
        ));
    }

    $temporaryFile = $thumbFile . '.' . uniqid('tmp_', true);

    if (!@move_uploaded_file($_FILES['thumbnail']['tmp_name'], $temporaryFile)) {
        jsonResponse(array(
            'success' => false,
            'error' => 'Cannot save thumbnail'
        ), 500);
    }

    // Atomic save
    if (!@rename($temporaryFile, $thumbFile)) {
        @unlink($temporaryFile);

        jsonResponse(array(
            'success' => false,
            'error' => 'Cannot finalize thumbnail'
        ), 500);
    }

    jsonResponse(array(
        'success' => true,
        'cached' => false,
        'thumbnail_url' => thumbnailUrl($relative)
    ));
}


// ==========================================
// 3. ONLINE VIEWER COUNT
// ==========================================

if ($action === 'heartbeat' || $action === 'leave' || $action === 'count') {

    if (!is_dir($presenceDir) && !@mkdir($presenceDir, 0775, true)) {
        jsonResponse(array(
            'success' => false,
            'error' => 'Presence storage is not writable'
        ), 500);
    }

    if ($action === 'count') {
        jsonResponse(array(
            'success' => true,
            'online_viewers' => activeViewerCount($presenceDir)
        ));
    }

    $sessionId = cleanSessionId(
        isset($_GET['session_id']) ? $_GET['session_id'] : ''
    );

    if ($sessionId === '') {
        jsonResponse(array(
            'success' => false,
            'error' => 'Invalid session_id'
        ), 400);
    }

    $file = $presenceDir . DIRECTORY_SEPARATOR . $sessionId . '.json';

    if ($action === 'leave') {
        @unlink($file);

        jsonResponse(array(
            'success' => true,
            'online_viewers' => activeViewerCount($presenceDir)
        ));
    }

    $payload = json_encode(array(
        'viewing' => true,
        'last_seen' => time()
    ));

    if (@file_put_contents($file, $payload, LOCK_EX) === false) {
        jsonResponse(array(
            'success' => false,
            'error' => 'Cannot write presence data'
        ), 500);
    }

    jsonResponse(array(
        'success' => true,
        'online_viewers' => activeViewerCount($presenceDir)
    ));
}


// ==========================================
// 4. VIDEO LIBRARY
// ==========================================

function scanVideos($folder, $root, $allowed) {

    $videos = array();

    $iterator = new RecursiveIteratorIterator(
        new RecursiveDirectoryIterator(
            $folder,
            FilesystemIterator::SKIP_DOTS
        )
    );

    foreach ($iterator as $file) {

        if (!$file->isFile()) {
            continue;
        }

        if (!in_array(strtolower($file->getExtension()), $allowed, true)) {
            continue;
        }

        $real = $file->getRealPath();

        if ($real === false ||
            strpos($real, $root . DIRECTORY_SEPARATOR) !== 0) {
            continue;
        }

        $relative = str_replace(
            DIRECTORY_SEPARATOR,
            '/',
            substr($real, strlen($root) + 1)
        );

        $urlPath = implode(
            '/',
            array_map('rawurlencode', explode('/', $relative))
        );

        $videos[] = array(
            'name' => $file->getBasename(),
            'path' => $relative,
            'url' => 'http://' . $_SERVER['HTTP_HOST'] . '/' . $urlPath,
            'size' => $file->getSize(),
            'thumbnail_url' => thumbnailUrl($relative)
        );
    }

    return $videos;
}


// ==========================================
// 5. BUILD VIDEO CATEGORIES
// ==========================================

try {

    $categories = array();

    foreach (new DirectoryIterator($root) as $entry) {

        if ($entry->isDot() || !$entry->isDir()) {
            continue;
        }

        $name = $entry->getFilename();

        if ($name[0] === '.' ||
            in_array(strtolower($name), array(
                'vendor',
                'node_modules',
                '.git',
                'thumb-cache'
            ), true)) {
            continue;
        }

        $videos = scanVideos(
            $entry->getPathname(),
            $root,
            $allowed
        );

        if (count($videos) > 0) {
            $categories[] = array(
                'name' => $name,
                'videos' => $videos
            );
        }
    }

    // Root-level videos
    $rootVideos = array();

    foreach (new DirectoryIterator($root) as $entry) {

        if (!$entry->isFile() ||
            !in_array(strtolower($entry->getExtension()), $allowed, true)) {
            continue;
        }

        $relative = $entry->getFilename();

        $rootVideos[] = array(
            'name' => $relative,
            'path' => $relative,
            'url' => 'http://' . $_SERVER['HTTP_HOST'] . '/' . rawurlencode($relative),
            'size' => $entry->getSize(),
            'thumbnail_url' => thumbnailUrl($relative)
        );
    }

    if (count($rootVideos) > 0) {
        $categories[] = array(
            'name' => 'অন্যান্য',
            'videos' => $rootVideos
        );
    }

    jsonResponse(array(
        'success' => true,
        'categories' => $categories,
        'online_viewers' => activeViewerCount($presenceDir)
    ));

} catch (Exception $e) {

    jsonResponse(array(
        'success' => false,
        'error' => 'রিসোর্স তালিকা তৈরি করা যায়নি।'
    ), 500);
}

?>
