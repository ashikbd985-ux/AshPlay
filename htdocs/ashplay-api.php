<?php
// Place this file in XAMPP/htdocs/ashplay-api.php
// Scans immediate subfolders of htdocs and lists supported video files recursively.

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');

$root = realpath(__DIR__);
$allowed = array('mp4', 'm4v', 'mkv', 'webm', 'avi', 'mov', '3gp', 'mpeg', 'mpg');
$categories = array();

function scanVideos($folder, $root, $categoryName, $allowed) {
    $videos = array();
    $iterator = new RecursiveIteratorIterator(
        new RecursiveDirectoryIterator($folder, FilesystemIterator::SKIP_DOTS)
    );

    foreach ($iterator as $file) {
        if (!$file->isFile()) continue;
        $extension = strtolower($file->getExtension());
        if (!in_array($extension, $allowed, true)) continue;

        $real = $file->getRealPath();
        if ($real === false || strpos($real, $root . DIRECTORY_SEPARATOR) !== 0) continue;

        $relative = str_replace(DIRECTORY_SEPARATOR, '/', substr($real, strlen($root) + 1));
        $urlPath = implode('/', array_map('rawurlencode', explode('/', $relative)));
        $videos[] = array(
            'name' => $file->getBasename(),
            'path' => $relative,
            'url' => 'http://' . $_SERVER['HTTP_HOST'] . '/' . $urlPath,
            'size' => $file->getSize()
        );
    }
    return $videos;
}

try {
    foreach (new DirectoryIterator($root) as $entry) {
        if ($entry->isDot() || !$entry->isDir()) continue;
        $name = $entry->getFilename();
        if ($name[0] === '.') continue;

        // Avoid scanning common server/system folders.
        if (in_array(strtolower($name), array('vendor', 'node_modules', '.git'), true)) continue;

        $videos = scanVideos($entry->getPathname(), $root, $name, $allowed);
        if (count($videos) > 0) {
            $categories[] = array('name' => $name, 'videos' => $videos);
        }
    }

    // Also include videos stored directly in htdocs under "অন্যান্য".
    $rootVideos = array();
    foreach (new DirectoryIterator($root) as $entry) {
        if (!$entry->isFile()) continue;
        if (in_array(strtolower($entry->getExtension()), $allowed, true)) {
            $relative = $entry->getFilename();
            $rootVideos[] = array(
                'name' => $entry->getFilename(),
                'path' => $relative,
                'url' => 'http://' . $_SERVER['HTTP_HOST'] . '/' . rawurlencode($relative),
                'size' => $entry->getSize()
            );
        }
    }
    if (count($rootVideos) > 0) {
        $categories[] = array('name' => 'অন্যান্য', 'videos' => $rootVideos);
    }

    echo json_encode(array('success' => true, 'categories' => $categories), JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
} catch (Exception $e) {
    http_response_code(500);
    echo json_encode(array('success' => false, 'error' => 'রিসোর্স তালিকা তৈরি করা যায়নি।'));
}
?>
