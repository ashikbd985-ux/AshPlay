# AshPlay Android Project

এটি AshPlay-এর প্রথম Home page-এর Android Studio source project। অ্যাপটি XAMPP `htdocs`-এর `ashplay-api.php` থেকে ক্যাটাগরি ও ভিডিও তালিকা নেয় এবং ভিডিও সরাসরি LAN server থেকে চালায়।

## GitHub-এ আপলোড
1. ZIP Extract করুন।
2. Extract করা `AshPlayProject` ফোল্ডারের ভেতরের সব ফাইল GitHub repository-র root-এ আপলোড করুন। `AshPlayProject` নামের অতিরিক্ত parent folder তৈরি করবেন না।
3. Commit changes করুন।
4. Actions tab → Build AshPlay APK → Run workflow। Push করলেও workflow স্বয়ংক্রিয়ভাবে চলবে।
5. সফল run-এর Artifacts থেকে `AshPlay-debug-APK` ডাউনলোড করুন। ZIP extract করে `app-debug.apk` ইনস্টল করুন।

## XAMPP API
- `htdocs/ashplay-api.php` ফাইলটি কম্পিউটারের `C:\xampp\htdocs\`-এ কপি করুন।
- ফোন ও কম্পিউটার একই Wi-Fi/LAN-এ রাখুন এবং Apache চালু রাখুন।
- API ঠিকানা বর্তমানে `http://192.168.168.10/ashplay-api.php`। কম্পিউটারের IP বদলালে `app/src/main/java/com/ashplay/MainActivity.kt`-এর `API_URL` বদলাতে হবে।
- API-তে ভিডিও ক্যাটাগরি/ফাইলগুলো XAMPP `htdocs`-এর প্রথম স্তরের ক্যাটাগরি ফোল্ডারে রাখুন।

## এই build-এ
- আপনার সরবরাহ করা আসল AshPlay logo ব্যবহার করা হয়েছে।
- হেডার: বামে TX এবং গত ২ সেকেন্ডে অ্যাপের received data, মাঝখানে আসল logo ও AshPlay, ডানে সবুজ status dot ও online count।
- সার্চ বার ও Search button।
- প্রতিটি category থেকে একটি random video নিয়ে vertical feed।
- ভিডিওর thumbnail ডিভাইসে সম্ভব হলে ভিডিওর প্রথম frame থেকে তৈরি হয়; codec/server সমর্থন না করলে fallback card দেখাবে।
- নিচে শুধু Home, Friends, Profile। Friends/Profile আপাতত placeholder screen; এগুলোর ফিচার পরবর্তী ধাপের কাজ।
- ভিডিও চালু থাকলে API-তে heartbeat পাঠায়; online count API থেকে refresh হয়।

## নোট
- TX হলো অ্যাপের UID-র inbound network bytes-এর ২ সেকেন্ডের delta; এটি শুধু নির্দিষ্ট ভিডিওর আলাদা bitrate নয়।
- প্রথম পেজের UI এখানে স্থির রাখা হয়েছে। Friends/Profile-এর অভ্যন্তরীণ ফিচার এখনো তৈরি করা হয়নি।
