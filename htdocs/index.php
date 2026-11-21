<?php
// AshPlay - single file: website + video streaming.

$root = realpath(__DIR__);
$allowed = array('mp4', 'm4v', 'mkv', 'webm', 'avi', 'mov', '3gp', 'mpeg', 'mpg');
$skipDirs = array('vendor', 'node_modules', 'thumb-cache', 'cgi-bin');

function resolveVideoPath($root, $relative, $allowed) {
    $relative = str_replace('\\', '/', (string)$relative);
    if ($relative === '' || strpos($relative, "\0") !== false || $relative[0] === '/') return false;
    foreach (explode('/', $relative) as $part) {
        if ($part === '' || $part === '.' || $part === '..') return false;
    }
    $candidate = realpath($root . DIRECTORY_SEPARATOR . str_replace('/', DIRECTORY_SEPARATOR, $relative));
    if ($candidate === false || !is_file($candidate)) return false;
    if (strpos($candidate, rtrim($root, DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR) !== 0) return false;
    if (!in_array(strtolower(pathinfo($candidate, PATHINFO_EXTENSION)), $allowed, true)) return false;
    return $candidate;
}

$action = isset($_GET['action']) ? (string)$_GET['action'] : '';

// ---------- Video streaming (with HTTP Range so seeking works) ----------
if ($action === 'video') {
    $relative = isset($_GET['path']) ? $_GET['path'] : '';
    $videoFile = resolveVideoPath($root, $relative, $allowed);

    if ($videoFile === false) {
        http_response_code(404);
        exit;
    }

    $fileSize = @filesize($videoFile);
    if ($fileSize === false || $fileSize < 1) {
        http_response_code(404);
        exit;
    }

    $extension = strtolower(pathinfo($videoFile, PATHINFO_EXTENSION));
    $mimeTypes = array(
        'mp4' => 'video/mp4',
        'm4v' => 'video/x-m4v',
        'mkv' => 'video/x-matroska',
        'webm' => 'video/webm',
        'avi' => 'video/x-msvideo',
        'mov' => 'video/quicktime',
        '3gp' => 'video/3gpp',
        'mpeg' => 'video/mpeg',
        'mpg' => 'video/mpeg'
    );

    $start = 0;
    $end = $fileSize - 1;

    header('Content-Type: ' . (isset($mimeTypes[$extension]) ? $mimeTypes[$extension] : 'application/octet-stream'));
    header('Accept-Ranges: bytes');
    header('X-Content-Type-Options: nosniff');
    header('Cache-Control: no-cache, no-store, must-revalidate');
    header('Pragma: no-cache');

    if (isset($_SERVER['HTTP_RANGE'])) {
        $rangeHeader = trim($_SERVER['HTTP_RANGE']);

        if (!preg_match('/^bytes=(\d*)-(\d*)$/', $rangeHeader, $matches)) {
            http_response_code(416);
            header('Content-Range: bytes */' . $fileSize);
            exit;
        }

        if ($matches[1] === '' && $matches[2] === '') {
            http_response_code(416);
            header('Content-Range: bytes */' . $fileSize);
            exit;
        }

        if ($matches[1] === '') {
            $suffixLength = (int)$matches[2];

            if ($suffixLength <= 0) {
                http_response_code(416);
                header('Content-Range: bytes */' . $fileSize);
                exit;
            }

            $start = max(0, $fileSize - $suffixLength);

        } else {
            $start = (int)$matches[1];

            if ($matches[2] !== '') {
                $end = (int)$matches[2];
            }
        }

        if ($start >= $fileSize || $start > $end) {
            http_response_code(416);
            header('Content-Range: bytes */' . $fileSize);
            exit;
        }

        $end = min($end, $fileSize - 1);

        http_response_code(206);
        header('Content-Range: bytes ' . $start . '-' . $end . '/' . $fileSize);
    }

    $contentLength = $end - $start + 1;
    header('Content-Length: ' . $contentLength);

    if ($_SERVER['REQUEST_METHOD'] === 'HEAD') {
        exit;
    }

    @ini_set('zlib.output_compression', 'Off');

    while (ob_get_level() > 0) {
        @ob_end_clean();
    }

    $handle = @fopen($videoFile, 'rb');

    if ($handle === false) {
        http_response_code(500);
        exit;
    }

    if (fseek($handle, $start) !== 0) {
        fclose($handle);
        http_response_code(500);
        exit;
    }

    $remaining = $contentLength;

    while ($remaining > 0 && !feof($handle)) {
        $chunkSize = min(65536, $remaining);
        $chunk = fread($handle, $chunkSize);

        if ($chunk === false || $chunk === '') {
            break;
        }

        echo $chunk;
        $remaining -= strlen($chunk);
        flush();

        if (connection_aborted()) {
            break;
        }
    }

    fclose($handle);
    exit;
}


// ---------- Scan videos ----------
function scanVideos($folder, $root, $allowed) {
    $videos = array();
    $it = new RecursiveIteratorIterator(new RecursiveDirectoryIterator($folder, FilesystemIterator::SKIP_DOTS));
    foreach ($it as $file) {
        if (!$file->isFile() || !in_array(strtolower($file->getExtension()), $allowed, true)) continue;
        $real = $file->getRealPath();
        if ($real === false || strpos($real, $root . DIRECTORY_SEPARATOR) !== 0) continue;
        $rel = str_replace(DIRECTORY_SEPARATOR, '/', substr($real, strlen($root) + 1));
        $videos[] = array(
            'title' => pathinfo($file->getBasename(), PATHINFO_FILENAME),
            'src' => '?action=video&path=' . rawurlencode($rel),
        );
    }
    usort($videos, function ($a, $b) { return strnatcasecmp($a['title'], $b['title']); });
    return $videos;
}

$categories = array();
$dirs = array();
foreach (new DirectoryIterator($root) as $e) {
    if ($e->isDot() || !$e->isDir()) continue;
    $n = $e->getFilename();
    if ($n[0] === '.' || in_array(strtolower($n), $skipDirs, true)) continue;
    $dirs[] = $e->getPathname();
}
natcasesort($dirs);
foreach ($dirs as $path) {
    $v = scanVideos($path, $root, $allowed);
    if ($v) $categories[] = array('name' => basename($path), 'videos' => $v);
}
// Videos placed directly in the root folder
$rootVideos = array();
foreach (new DirectoryIterator($root) as $e) {
    if (!$e->isFile() || !in_array(strtolower($e->getExtension()), $allowed, true)) continue;
    $rootVideos[] = array(
        'title' => pathinfo($e->getFilename(), PATHINFO_FILENAME),
        'src' => '?action=video&path=' . rawurlencode($e->getFilename()),
    );
}
if ($rootVideos) {
    usort($rootVideos, function ($a, $b) { return strnatcasecmp($a['title'], $b['title']); });
    $categories[] = array('name' => 'Other', 'videos' => $rootVideos);
}

header('Content-Type: text/html; charset=utf-8');
header('Cache-Control: no-store, no-cache, must-revalidate, max-age=0');
$data = json_encode($categories, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_HEX_TAG | JSON_HEX_AMP | JSON_HEX_APOS | JSON_HEX_QUOT);
?>
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#0f0f12">
<title>AshPlay</title>
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;background:#0f0f12;color:#f2f2f5;font-family:system-ui,-apple-system,sans-serif}
header{position:sticky;top:0;z-index:5;display:flex;align-items:center;gap:10px;padding:10px 14px;background:#0f0f12;border-bottom:1px solid #22222a}
header img{width:32px;height:32px;border-radius:8px}
header h1{margin:0;font-size:20px}
.back{display:none;background:none;border:0;color:#fff;font-size:24px;padding:0 6px 0 0;cursor:pointer}
main{padding:12px 12px 40px;max-width:900px;margin:0 auto}
.card{display:block;width:100%;margin:0 0 18px;padding:0;background:#18181d;border:0;border-radius:14px;overflow:hidden;text-align:left;color:inherit;cursor:pointer}
.thumb{position:relative;aspect-ratio:16/9;background:#000}
.thumb video{width:100%;height:100%;object-fit:cover;display:block;pointer-events:none}
.play{position:absolute;inset:0;margin:auto;width:54px;height:54px;border-radius:50%;background:rgba(0,0,0,.55);display:flex;align-items:center;justify-content:center}
.play:after{content:"";border-left:18px solid #fff;border-top:11px solid transparent;border-bottom:11px solid transparent;margin-left:4px}
.meta{padding:10px 12px 12px}
.cat{font-size:13px;color:#ff7a45;font-weight:600;margin-bottom:3px}
.ttl{font-size:16px;line-height:1.35;word-break:break-word}
.count{font-size:12px;color:#8d8d99;margin-top:4px}
#player{width:100%;aspect-ratio:16/9;background:#000;display:block;border-radius:12px}
.now{padding:12px 2px 6px}
.now .ttl{font-size:18px;font-weight:600}
h2{font-size:15px;margin:18px 0 8px;color:#c9c9d3}
.item{display:flex;gap:10px;align-items:center;width:100%;margin:0 0 8px;padding:8px;background:#18181d;border:0;border-radius:12px;color:inherit;text-align:left;cursor:pointer}
.item.active{outline:2px solid #ff7a45}
.item .t{position:relative;flex:0 0 128px;aspect-ratio:16/9;background:#000;border-radius:8px;overflow:hidden}
.item .t video{width:100%;height:100%;object-fit:cover;display:block;pointer-events:none}
.item .n{font-size:14px;line-height:1.35;word-break:break-word}
.empty{text-align:center;color:#8d8d99;padding:60px 10px}
</style>
</head>
<body>
<header>
  <button class="back" id="back" aria-label="Back">&#8592;</button>
  <img src="ashplay_logo.png" alt="" onerror="this.style.display='none'">
  <h1>AshPlay</h1>
</header>
<main id="app"></main>
<script>
var CATS = <?php echo $data; ?>;
var app = document.getElementById('app'), backBtn = document.getElementById('back');

function esc(s){return String(s).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]})}
function thumb(src){return '<video src="'+esc(src)+'#t=0.5" preload="metadata" muted playsinline></video>'}

function home(){
  backBtn.style.display='none';
  if(!CATS.length){app.innerHTML='<div class="empty">No videos found.</div>';return}
  app.innerHTML=CATS.map(function(c,i){
    var v=c.videos[0];
    return '<button class="card" data-c="'+i+'"><div class="thumb">'+thumb(v.src)+'<div class="play"></div></div>'+
      '<div class="meta"><div class="cat">'+esc(c.name)+'</div><div class="ttl">'+esc(v.title)+'</div>'+
      '<div class="count">'+c.videos.length+' videos</div></div></button>';
  }).join('');
  window.scrollTo(0,0);
}

function watch(ci,vi,autoplay){
  var c=CATS[ci]; if(!c){return home()}
  vi=Math.min(Math.max(vi||0,0),c.videos.length-1);
  var v=c.videos[vi];
  backBtn.style.display='block';
  app.innerHTML='<video id="player" src="'+esc(v.src)+'" controls playsinline controlsList="nodownload"></video>'+
    '<div class="now"><div class="cat">'+esc(c.name)+'</div><div class="ttl">'+esc(v.title)+'</div></div>'+
    '<h2>'+esc(c.name)+' - all videos ('+c.videos.length+')</h2>'+
    c.videos.map(function(x,j){
      return '<button class="item'+(j===vi?' active':'')+'" data-c="'+ci+'" data-v="'+j+'"><div class="t">'+thumb(x.src)+'</div><div class="n">'+esc(x.title)+'</div></button>';
    }).join('');
  var p=document.getElementById('player');
  if(autoplay){var r=p.play(); if(r&&r.catch)r.catch(function(){})}
  p.addEventListener('ended',function(){ if(vi+1<c.videos.length) location.hash='#/'+ci+'/'+(vi+1)+'/p'; });
  window.scrollTo(0,0);
}

function route(){
  var m=location.hash.match(/^#\/(\d+)\/(\d+)(\/p)?$/);
  if(m) watch(+m[1],+m[2],!!m[3]); else home();
}
app.addEventListener('click',function(e){
  var b=e.target.closest('button[data-c]'); if(!b)return;
  var ci=b.getAttribute('data-c'), vi=b.getAttribute('data-v')||0;
  location.hash='#/'+ci+'/'+vi+'/p';
});
backBtn.addEventListener('click',function(){ if(history.length>1) history.back(); else location.hash=''; });
window.addEventListener('hashchange',route);
route();
</script>
</body>
</html>
