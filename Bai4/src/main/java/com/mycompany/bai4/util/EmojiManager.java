package com.mycompany.bai4.util;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trung tâm quản lý kho Emoji đa sắc, bộ nhớ đệm hình ảnh (Cache),
 * tìm kiếm song ngữ Việt - Anh, lưu trữ Emoji gần đây theo tài khoản
 * và ánh xạ chuỗi Unicode Emoji sang hình ảnh màu sắc trong tin nhắn.
 *
 * Hỗ trợ nạp tài nguyên PNG offline từ thư mục /emojis/ (chuẩn Twemoji CC-BY 4.0 / OpenMoji)
 * kết hợp bộ dựng Vector Graphics2D đa sắc nội bộ giúp hoạt động 100% offline.
 */
public class EmojiManager {

    public enum Category {
        RECENT("Gần đây", "🕒"),
        SMILEYS("Mặt cười & Cảm xúc", "😀"),
        PEOPLE("Con người & Cơ thể", "👋"),
        ANIMALS("Động vật & Thiên nhiên", "🐶"),
        FOOD("Đồ ăn & Thức uống", "🍔"),
        ACTIVITIES("Hoạt động & Thể thao", "⚽"),
        TRAVEL("Du lịch & Phương tiện", "✈️"),
        OBJECTS("Đồ vật", "💡"),
        SYMBOLS("Biểu tượng", "❤️"),
        FLAGS("Cờ quốc gia", "🇻🇳");

        public final String title;
        public final String repEmoji;

        Category(String title, String repEmoji) {
            this.title = title;
            this.repEmoji = repEmoji;
        }
    }

    public record EmojiItem(
            String unicode,
            String viName,
            String keywords,
            Category category,
            String normalizedSearchText
    ) {
        public EmojiItem(String unicode, String viName, String keywords, Category category) {
            this(unicode, viName, keywords, category,
                    normalizeForSearch(viName + " " + keywords + " " + unicode));
        }
    }

    private static final int MAX_RECENT = 24;
    private static final List<EmojiItem> ALL_EMOJIS = new ArrayList<>();
    private static final Map<String, EmojiItem> UNICODE_MAP = new HashMap<>();
    private static final List<String> SORTED_UNICODES_DESC = new ArrayList<>();
    private static final ConcurrentHashMap<String, ImageIcon> ICON_CACHE = new ConcurrentHashMap<>();

    static {
        initCatalog();
    }

    private static void add(Category cat, String unicode, String viName, String enKeywords) {
        String cleanKey = stripVariationSelector(unicode);
        EmojiItem item = new EmojiItem(unicode, viName, enKeywords, cat);
        ALL_EMOJIS.add(item);
        UNICODE_MAP.put(unicode, item);
        UNICODE_MAP.put(cleanKey, item);
    }

    private static void initCatalog() {
        // ── 2. Mặt cười và cảm xúc (SMILEYS) ──
        add(Category.SMILEYS, "😀", "Mặt cười tươi", "grinning smile happy vui cuoi tuoi");
        add(Category.SMILEYS, "😃", "Cười mở miệng lớn", "smiley happy joy vui vẻ ha ha");
        add(Category.SMILEYS, "😄", "Cười tít mắt", "smile happy laugh cuoi tit mat vui");
        add(Category.SMILEYS, "😁", "Cười nhe răng", "grin teeth happy cuoi nhe rang");
        add(Category.SMILEYS, "😆", "Cười híp mắt", "laughing satisfied haha cuoi lon");
        add(Category.SMILEYS, "😅", "Cười toát mồ hôi", "sweat smile nervous ngai ngung");
        add(Category.SMILEYS, "🤣", "Cười lăn lộn", "rofl rolling laughing cuoi lan lon");
        add(Category.SMILEYS, "😂", "Cười ra nước mắt", "joy tears laugh haha cuoi ra nuoc mat");
        add(Category.SMILEYS, "🙂", "Mỉm cười nhẹ", "slightly smiling mim cuoi");
        add(Category.SMILEYS, "🙃", "Mặt lộn ngược", "upside down silly nghich ngom");
        add(Category.SMILEYS, "😉", "Nháy mắt", "wink nhay mat");
        add(Category.SMILEYS, "😊", "Cười đỏ mặt", "blush shy happy cuoi e then do mat");
        add(Category.SMILEYS, "😇", "Thiên thần", "innocent angel halo hien lanh thien than");
        add(Category.SMILEYS, "🥰", "Yêu thương ngập tràn", "smiling hearts love yeu thuong");
        add(Category.SMILEYS, "😍", "Mắt trái tim", "heart eyes love me man yeu");
        add(Category.SMILEYS, "🤩", "Mắt ngôi sao", "star struck excited hao hung");
        add(Category.SMILEYS, "😘", "Hôn gió", "kissing heart love hon gio");
        add(Category.SMILEYS, "😗", "Chu môi hôn", "kissing chu moi");
        add(Category.SMILEYS, "😋", "Ngon miệng", "yum delicious tasty ngon");
        add(Category.SMILEYS, "😛", "Lè lưỡi", "stuck out tongue le luoi");
        add(Category.SMILEYS, "😜", "Nháy mắt lè lưỡi", "winking tongue crazy tinh nghich");
        add(Category.SMILEYS, "🤪", "Nhắng nhít", "zany crazy goofy nhang nhit");
        add(Category.SMILEYS, "😎", "Đeo kính râm cực ngầu", "sunglasses cool ngau");
        add(Category.SMILEYS, "🤓", "Mọt sách", "nerd glasses tri thuc mot sach");
        add(Category.SMILEYS, "🥳", "Ăn mừng tiệc tùng", "partying celebrate sinh nhat an mung");
        add(Category.SMILEYS, "😏", "Cười nhếch mép", "smirk confident nhech mep");
        add(Category.SMILEYS, "😒", "Không hài lòng", "unamused bored chan");
        add(Category.SMILEYS, "😞", "Thất vọng", "disappointed sad buồn that vong");
        add(Category.SMILEYS, "😔", "Buồn rầu", "pensive sad buon");
        add(Category.SMILEYS, "😟", "Lo lắng", "worried anxious lo lang");
        add(Category.SMILEYS, "😕", "Bối rối", "confused boi roi");
        add(Category.SMILEYS, "🙁", "Hơi buồn", "slightly frowning buon nhe");
        add(Category.SMILEYS, "😣", "Đau khổ", "persevere dau kho");
        add(Category.SMILEYS, "😖", "Khó chịu", "confounded kho chiu");
        add(Category.SMILEYS, "😫", "Mệt mỏi", "tired exhausted met moi");
        add(Category.SMILEYS, "😩", "Kiệt sức", "weary sad kiet suc");
        add(Category.SMILEYS, "🥺", "Mắt long lanh năn nỉ", "pleading puppy eyes nan ni");
        add(Category.SMILEYS, "😢", "Khóc rơi nước mắt", "cry tear sad khoc buon");
        add(Category.SMILEYS, "😭", "Khóc nức nở", "sob loud crying khoc to");
        add(Category.SMILEYS, "😤", "Phẫn nộ thở hắt", "triumph steam angry tuc gian");
        add(Category.SMILEYS, "😠", "Tức giận", "angry mad gian du");
        add(Category.SMILEYS, "😡", "Giận đỏ mặt", "rage furious pouting cuc gian");
        add(Category.SMILEYS, "🤬", "Tức điên lên", "cursing swearing angry gian");
        add(Category.SMILEYS, "🤯", "Nổ tung đầu", "exploding head mind blown bat ngo");
        add(Category.SMILEYS, "😳", "Đỏ mặt ngạc nhiên", "flushed surprised ngac nhien");
        add(Category.SMILEYS, "🥵", "Nóng bức", "hot sweating nong");
        add(Category.SMILEYS, "🥶", "Đóng băng lạnh giá", "cold freezing lanh");
        add(Category.SMILEYS, "😱", "Sợ hãi la hét", "scream fear shocked hoang hot");
        add(Category.SMILEYS, "😨", "Hoảng sợ", "fearful scared so hai");
        add(Category.SMILEYS, "😰", "Lo sợ toát mồ hôi", "anxious cold sweat lo so");
        add(Category.SMILEYS, "🤔", "Suy nghĩ", "thinking hmm suy nghi");
        add(Category.SMILEYS, "🤫", "Suỵt giữ bí mật", "shushing quiet im lang bi mat");
        add(Category.SMILEYS, "🤭", "Che miệng cười", "hand over mouth giggle che mieng");
        add(Category.SMILEYS, "🥱", "Ngáp buồn ngủ", "yawning sleepy buon ngu");
        add(Category.SMILEYS, "😴", "Đang ngủ", "sleeping zzz ngu");
        add(Category.SMILEYS, "😷", "Đeo khẩu trang", "mask sick doctor khau trang om");
        add(Category.SMILEYS, "🤒", "Bị sốt", "thermometer fever sick bi sot");
        add(Category.SMILEYS, "🤕", "Băng đầu", "head bandage hurt bi thuong");
        add(Category.SMILEYS, "😮", "Ngạc nhiên", "open mouth surprised wow ngac nhien");
        // ── 3. Con người và cơ thể (PEOPLE) ──
        add(Category.PEOPLE, "👍", "Thích / Tuyệt vời", "thumbs up like good ok tot thich");
        add(Category.PEOPLE, "👎", "Không thích", "thumbs down dislike bad che");
        add(Category.PEOPLE, "👌", "OK hoàn hảo", "ok hand perfect dong y");
        add(Category.PEOPLE, "✌️", "Chiến thắng / Xin chào", "victory peace hi chien thang");
        add(Category.PEOPLE, "🤞", "Chúc may mắn", "crossed fingers luck may man");
        add(Category.PEOPLE, "🤟", "Yêu bạn", "love you gesture yeu");
        add(Category.PEOPLE, "🤘", "Rock cuồng nhiệt", "sign of horns rock");
        add(Category.PEOPLE, "🤙", "Gọi cho tôi", "call me hand goi dien");
        add(Category.PEOPLE, "👋", "Vẫy tay chào", "wave hello goodbye xin chao tam biet");
        add(Category.PEOPLE, "🤚", "Giơ bàn tay", "raised back of hand gio tay");
        add(Category.PEOPLE, "✋", "Đập tay High Five", "raised hand stop high five");
        add(Category.PEOPLE, "👏", "Vỗ tay khen ngợi", "clap applause bravo vo tay");
        add(Category.PEOPLE, "🙌", "Hoan hô chiến thắng", "raising hands hooray hoan ho");
        add(Category.PEOPLE, "👐", "Mở rộng vòng tay", "open hands hug");
        add(Category.PEOPLE, "🤲", "Chắp tay đón nhận", "palms up together");
        add(Category.PEOPLE, "🤝", "Bắt tay hợp tác", "handshake deal friend bat tay");
        add(Category.PEOPLE, "🙏", "Cảm ơn / Cầu nguyện", "folded hands pray thank you cam on xin loi");
        add(Category.PEOPLE, "💪", "Mạnh mẽ cơ bắp", "flexed biceps strong muscle co len manh me");
        add(Category.PEOPLE, "✍️", "Đang viết", "writing hand viet bai");
        add(Category.PEOPLE, "👀", "Đôi mắt nhìn", "eyes look watch mat nhin");
        add(Category.PEOPLE, "🧠", "Bộ não thông minh", "brain smart intelligence thong minh");
        add(Category.PEOPLE, "👑", "Vương miện vua", "crown king queen vip vuong mien");

        // ── 4. Động vật và thiên nhiên (ANIMALS) ──
        add(Category.ANIMALS, "🐶", "Chó con dễ thương", "dog puppy pet cho");
        add(Category.ANIMALS, "🐱", "Mèo con đáng yêu", "cat kitten pet meo");
        add(Category.ANIMALS, "🐭", "Chuột nhỏ", "mouse rat chuot");
        add(Category.ANIMALS, "🐹", "Chuột Hamster", "hamster pet");
        add(Category.ANIMALS, "🐰", "Thỏ trắng", "rabbit bunny tho");
        add(Category.ANIMALS, "🦊", "Cáo đỏ", "fox cao");
        add(Category.ANIMALS, "🐻", "Gấu nâu", "bear gau");
        add(Category.ANIMALS, "🐼", "Gấu trúc Panda", "panda gau truc");
        add(Category.ANIMALS, "🐨", "Gấu Koala", "koala");
        add(Category.ANIMALS, "🐯", "Hổ vằn", "tiger ho");
        add(Category.ANIMALS, "🦁", "Sư tử", "lion su tu");
        add(Category.ANIMALS, "🐮", "Bò sữa", "cow bo");
        add(Category.ANIMALS, "🐷", "Heo hồng", "pig heo lon");
        add(Category.ANIMALS, "🐸", "Ếch xanh", "frog ech");
        add(Category.ANIMALS, "🐵", "Khỉ con", "monkey khi");
        add(Category.ANIMALS, "🐔", "Gà trống", "chicken ga");
        add(Category.ANIMALS, "🐧", "Chim cánh cụt", "penguin canh cut");
        add(Category.ANIMALS, "🦋", "Bươm bướm", "butterfly buom");
        add(Category.ANIMALS, "🐝", "Ong chăm chỉ", "bee honey ong");
        add(Category.ANIMALS, "🌹", "Hoa hồng đỏ", "rose flower love hoa hong");
        add(Category.ANIMALS, "🌸", "Hoa anh đào", "cherry blossom sakura hoa anh dao");
        add(Category.ANIMALS, "🌻", "Hoa hướng dương", "sunflower hoa huong duong");
        add(Category.ANIMALS, "🍀", "Cỏ bốn lá may mắn", "four leaf clover lucky co 4 la");
        add(Category.ANIMALS, "🌴", "Cây dừa biển", "palm tree cay dua");
        add(Category.ANIMALS, "🌈", "Cầu vồng", "rainbow cau vong");
        add(Category.ANIMALS, "☀️", "Mặt trời tỏa nắng", "sun sunny mat troi nang");
        add(Category.ANIMALS, "🌙", "Mặt trăng khuyết", "crescent moon night mat trang");
        add(Category.ANIMALS, "⭐", "Ngôi sao vàng", "star ngoi sao");

        // ── 5. Đồ ăn và thức uống (FOOD) ──
        add(Category.FOOD, "🍎", "Táo đỏ", "red apple fruit qua tao");
        add(Category.FOOD, "🍉", "Dưa hấu", "watermelon dua hau");
        add(Category.FOOD, "🍓", "Dâu tây", "strawberry dau tay");
        add(Category.FOOD, "🍌", "Quả chuối", "banana chuoi");
        add(Category.FOOD, "🍍", "Quả dứa", "pineapple dua");
        add(Category.FOOD, "🥭", "Quả xoài", "mango xoai");
        add(Category.FOOD, "🍑", "Quả đào", "peach dao");
        add(Category.FOOD, "🍔", "Bánh Hamburger", "hamburger burger fastfood");
        add(Category.FOOD, "🍕", "Bánh Pizza", "pizza");
        add(Category.FOOD, "🍟", "Khoai tây chiên", "french fries khoai tay chien");
        add(Category.FOOD, "🌭", "Xúc xích Hotdog", "hotdog xuc xich");
        add(Category.FOOD, "🍜", "Mì nóng / Phở", "noodles ramen pho bun mi");
        add(Category.FOOD, "🍣", "Sushi Nhật Bản", "sushi");
        add(Category.FOOD, "🍱", "Cơm hộp Bento", "bento box com");
        add(Category.FOOD, "🍰", "Bánh kem dâu", "shortcake cake banh ngot");
        add(Category.FOOD, "🎂", "Bánh sinh nhật", "birthday cake banh sinh nhat");
        add(Category.FOOD, "🍩", "Bánh Donut", "doughnut donut");
        add(Category.FOOD, "🍦", "Kem ốc quế", "ice cream kem");
        add(Category.FOOD, "☕", "Cà phê nóng", "coffee tea cafe ca phe tra");
        add(Category.FOOD, "🧋", "Trà sữa trân châu", "bubble tea boba tra sua");
        add(Category.FOOD, "🍺", "Ly bia cụng ly", "beer mug bia");
        add(Category.FOOD, "🍻", "Cụng ly chúc mừng", "clinking beer mugs dzô cung ly");
        add(Category.FOOD, "🍷", "Rượu vang đỏ", "wine glass ruou vang");
        add(Category.FOOD, "🥤", "Nước ngọt có ống hút", "cup with straw nuoc ngot");

        // ── 6. Hoạt động và thể thao (ACTIVITIES) ──
        add(Category.ACTIVITIES, "⚽", "Bóng đá", "soccer ball football bong da");
        add(Category.ACTIVITIES, "🏀", "Bóng rổ", "basketball bong ro");
        add(Category.ACTIVITIES, "🏐", "Bóng chuyền", "volleyball bong chuyen");
        add(Category.ACTIVITIES, "🎾", "Quần vợt Tennis", "tennis");
        add(Category.ACTIVITIES, "🏸", "Cầu lông", "badminton cau long");
        add(Category.ACTIVITIES, "🏓", "Bóng bàn", "ping pong table tennis bong ban");
        add(Category.ACTIVITIES, "🏆", "Cúp vô địch", "trophy champion winner cup vo dich");
        add(Category.ACTIVITIES, "🥇", "Huy chương vàng", "first place gold medal huy chuong vang");
        add(Category.ACTIVITIES, "🎖️", "Huân chương danh dự", "military medal huan chuong");
        add(Category.ACTIVITIES, "🎮", "Tay cầm chơi Game", "video game controller choi game");
        add(Category.ACTIVITIES, "🎧", "Tai nghe âm nhạc", "headphone music nghe nhac");
        add(Category.ACTIVITIES, "🎤", "Micro hát Karaoke", "microphone karaoke ca hat");
        add(Category.ACTIVITIES, "🎬", "Phim điện ảnh", "clapper board movie xem phim");
        add(Category.ACTIVITIES, "🎨", "Bảng màu hội họa", "artist palette art ve tranh");
        add(Category.ACTIVITIES, "🎯", "Trúng hồng tâm", "bullseye target muc tieu");
        add(Category.ACTIVITIES, "🎉", "Pháo giấy chúc mừng", "party popper celebrate chuc mung");
        add(Category.ACTIVITIES, "🎊", "Quả cầu lễ hội", "confetti ball le hoi");
        add(Category.ACTIVITIES, "🎁", "Hộp quà tặng", "wrapped gift present hop qua");

        // ── 7. Du lịch và phương tiện (TRAVEL) ──
        add(Category.TRAVEL, "🚗", "Xe hơi ô tô", "car automobile xe o to");
        add(Category.TRAVEL, "🚕", "Xe Taxi", "taxi cab");
        add(Category.TRAVEL, "🚌", "Xe buýt", "bus xe buyt");
        add(Category.TRAVEL, "🏎️", "Xe đua tốc độ", "racing car xe dua");
        add(Category.TRAVEL, "🚓", "Xe cảnh sát", "police car");
        add(Category.TRAVEL, "🚑", "Xe cứu thương", "ambulance");
        add(Category.TRAVEL, "🚒", "Xe cứu hỏa", "fire engine");
        add(Category.TRAVEL, "🛵", "Xe tay ga / Xe máy", "motor scooter motorbike xe may");
        add(Category.TRAVEL, "🚲", "Xe đạp", "bicycle bike xe dap");
        add(Category.TRAVEL, "✈️", "Máy bay du lịch", "airplane flight travel may bay du lich");
        add(Category.TRAVEL, "🚀", "Tên lửa tăng tốc", "rocket launch fast ten lua");
        add(Category.TRAVEL, "🚁", "Trực thăng", "helicopter");
        add(Category.TRAVEL, "⛵", "Thuyền buồm", "sailboat boat thuyen");
        add(Category.TRAVEL, "🏖️", "Bãi biển nghỉ mát", "beach umbrella bai bien");
        add(Category.TRAVEL, "🏠", "Ngôi nhà ấm áp", "house home nha");
        add(Category.TRAVEL, "🏢", "Tòa nhà văn phòng", "office building cong ty");

        // ── 8. Đồ vật (OBJECTS) ──
        add(Category.OBJECTS, "💻", "Máy tính Laptop", "laptop computer code may tinh");
        add(Category.OBJECTS, "📱", "Điện thoại thông minh", "mobile phone smartphone dien thoai");
        add(Category.OBJECTS, "⌨️", "Bàn phím", "keyboard ban phim");
        add(Category.OBJECTS, "📷", "Máy ảnh", "camera chup hinh");
        add(Category.OBJECTS, "💡", "Bóng đèn ý tưởng", "light bulb idea y tuong");
        add(Category.OBJECTS, "🔦", "Đèn pin", "flashlight");
        add(Category.OBJECTS, "📚", "Sách vở học tập", "books study hoc tap sach");
        add(Category.OBJECTS, "📝", "Ghi chú Memo", "memo note write ghi chu");
        add(Category.OBJECTS, "📁", "Thư mục hồ sơ", "file folder thu muc");
        add(Category.OBJECTS, "📌", "Đinh ghim", "pushpin pin ghim");
        add(Category.OBJECTS, "📎", "Kẹp giấy", "paperclip dinh kem");
        add(Category.OBJECTS, "🔑", "Chìa khóa vàng", "key chia khoa");
        add(Category.OBJECTS, "🔒", "Ổ khóa bảo mật", "locked security khoa");
        add(Category.OBJECTS, "🔔", "Chuông thông báo", "bell notification chuong thong bao");
        add(Category.OBJECTS, "💰", "Túi tiền vàng", "money bag rich tien");
        add(Category.OBJECTS, "💎", "Kim cương quý giá", "gem stone diamond kim cuong");
        add(Category.OBJECTS, "⏰", "Đồng hồ báo thức", "alarm clock time dong ho");
        add(Category.OBJECTS, "💊", "Viên thuốc", "pill medicine thuoc");

        // ── 9. Biểu tượng (SYMBOLS) ──
        add(Category.SYMBOLS, "❤️", "Trái tim đỏ", "red heart love yeu trai tim do");
        add(Category.SYMBOLS, "🧡", "Trái tim cam", "orange heart trai tim cam");
        add(Category.SYMBOLS, "💛", "Trái tim vàng", "yellow heart trai tim vang");
        add(Category.SYMBOLS, "💚", "Trái tim xanh lá", "green heart trai tim xanh la");
        add(Category.SYMBOLS, "💙", "Trái tim xanh dương", "blue heart trai tim xanh duong");
        add(Category.SYMBOLS, "💜", "Trái tim tím", "purple heart trai tim tim");
        add(Category.SYMBOLS, "💖", "Trái tim lấp lánh", "sparkling heart love trai tim lap lanh");
        add(Category.SYMBOLS, "💘", "Trái tim mũi tên thần tình yêu", "heart with arrow cupid");
        add(Category.SYMBOLS, "💔", "Trái tim tan vỡ", "broken heart sad tan vo");
        add(Category.SYMBOLS, "🔥", "Ngọn lửa rực cháy", "fire hot lit lua");
        add(Category.SYMBOLS, "✨", "Lấp lánh phép thuật", "sparkles magic stars lap lanh");
        add(Category.SYMBOLS, "💯", "100 điểm tuyệt đối", "hundred points perfect 100 diem");
        add(Category.SYMBOLS, "✅", "Dấu tích hoàn thành", "check mark button done ok xong");
        add(Category.SYMBOLS, "❌", "Dấu chéo hủy bỏ", "cross mark cancel wrong sai");
        add(Category.SYMBOLS, "⚠️", "Cảnh báo chú ý", "warning alert canh bao");
        add(Category.SYMBOLS, "❓", "Dấu chấm hỏi", "question mark hoi");
        add(Category.SYMBOLS, "❗", "Dấu chấm than", "exclamation mark");
        add(Category.SYMBOLS, "💬", "Bong bóng trò chuyện", "speech balloon chat tin nhan");
        add(Category.SYMBOLS, "💤", "Biểu tượng buồn ngủ", "zzz sleep ngu");
        add(Category.SYMBOLS, "🎵", "Nốt nhạc vui", "musical note nhac");

        // ── 10. Cờ quốc gia (FLAGS) ──
        add(Category.FLAGS, "🇻🇳", "Cờ Việt Nam", "vietnam flag co viet nam vn");
        add(Category.FLAGS, "🇯🇵", "Cờ Nhật Bản", "japan flag co nhat ban jp");
        add(Category.FLAGS, "🇰🇷", "Cờ Hàn Quốc", "south korea flag co han quoc kr");
        add(Category.FLAGS, "🇺🇸", "Cờ Hoa Kỳ (Mỹ)", "united states usa flag co my");
        add(Category.FLAGS, "🇬🇧", "Cờ Vương quốc Anh", "united kingdom uk flag co anh");
        add(Category.FLAGS, "🇫🇷", "Cờ Pháp", "france flag co phap");
        add(Category.FLAGS, "🇩🇪", "Cờ Đức", "germany flag co duc");
        add(Category.FLAGS, "🇨🇳", "Cờ Trung Quốc", "china flag co trung quoc");
        add(Category.FLAGS, "🇹🇭", "Cờ Thái Lan", "thailand flag co thai lan");
        add(Category.FLAGS, "🇸🇬", "Cờ Singapore", "singapore flag");
        add(Category.FLAGS, "🇦🇺", "Cờ Úc", "australia flag co uc");
        add(Category.FLAGS, "🇨🇦", "Cờ Canada", "canada flag");

        SORTED_UNICODES_DESC.addAll(UNICODE_MAP.keySet());
        SORTED_UNICODES_DESC.sort((a, b) -> Integer.compare(b.length(), a.length()));
    }

    public static List<EmojiItem> getByCategory(Category category, String username) {
        if (category == Category.RECENT) {
            return getRecentEmojis(username);
        }
        List<EmojiItem> list = new ArrayList<>();
        for (EmojiItem item : ALL_EMOJIS) {
            if (item.category() == category) {
                list.add(item);
            }
        }
        return list;
    }

    public static List<EmojiItem> search(String query) {
        if (query == null || query.isBlank()) {
            return new ArrayList<>(ALL_EMOJIS);
        }
        String normQuery = normalizeForSearch(query);
        String[] tokens = normQuery.split("\\s+");
        List<EmojiItem> results = new ArrayList<>();
        for (EmojiItem item : ALL_EMOJIS) {
            boolean matchAll = true;
            for (String tk : tokens) {
                if (!item.normalizedSearchText().contains(tk)) {
                    matchAll = false;
                    break;
                }
            }
            if (matchAll) {
                results.add(item);
            }
        }
        return results;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Quản lý Emoji gần đây (Lưu bền vững theo từng username)
    // ══════════════════════════════════════════════════════════════════════════

    private static Path getRecentFilePath(String username) {
        String safeUser = (username == null || username.isBlank())
                ? "default"
                : username.replaceAll("[^a-zA-Z0-9_\\-\\.]", "_");
        Path dir = Paths.get(System.getProperty("user.home"), ".zalo_chat_emojis");
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {}
        return dir.resolve("recent_" + safeUser + ".txt");
    }

    public static synchronized List<EmojiItem> getRecentEmojis(String username) {
        Path file = getRecentFilePath(username);
        List<EmojiItem> result = new ArrayList<>();
        if (Files.exists(file)) {
            try {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (String u : lines) {
                    String trimmed = u.trim();
                    if (trimmed.isEmpty()) continue;
                    EmojiItem item = findEmojiItem(trimmed);
                    if (item != null && !result.contains(item)) {
                        result.add(item);
                        if (result.size() >= MAX_RECENT) break;
                    }
                }
            } catch (IOException ignored) {}
        }
        // Nếu tài khoản mới chưa có lịch sử, gợi ý sẵn 16 Emoji thông dụng nhất
        if (result.isEmpty()) {
            String[] defaults = {"😂", "❤️", "👍", "🥰", "😭", "🙏", "🎉", "🔥", "😀", "😎", "🤔", "🌹", "✅", "🤝", "☕", "🇻🇳"};
            for (String d : defaults) {
                EmojiItem item = findEmojiItem(d);
                if (item != null) result.add(item);
            }
        }
        return result;
    }

    public static synchronized void recordEmojiUsage(String username, String unicode) {
        if (unicode == null || unicode.isBlank()) return;
        Path file = getRecentFilePath(username);
        LinkedHashSet<String> set = new LinkedHashSet<>();
        set.add(unicode.trim());

        if (Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (!t.isEmpty()) set.add(t);
                    if (set.size() >= MAX_RECENT) break;
                }
            } catch (IOException ignored) {}
        }
        try {
            Files.write(file, set, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    public static EmojiItem findEmojiItem(String unicode) {
        if (unicode == null) return null;
        EmojiItem item = UNICODE_MAP.get(unicode);
        if (item != null) return item;
        return UNICODE_MAP.get(stripVariationSelector(unicode));
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Bộ tạo hình ảnh Emoji đa sắc + Bộ nhớ đệm (Cache)
    // ══════════════════════════════════════════════════════════════════════════

    public static ImageIcon getEmojiIcon(String unicode, int size) {
        if (unicode == null || unicode.isEmpty()) return null;
        String clean = stripVariationSelector(unicode);
        String key = clean + "@" + size;
        return ICON_CACHE.computeIfAbsent(key, k -> new ImageIcon(renderEmojiImage(unicode, size)));
    }

    private static BufferedImage renderEmojiImage(String unicode, int size) {
        // 1. Ưu tiên đọc từ tài nguyên PNG đóng gói trong project (/emojis/<codepoint>.png) nếu có
        String hexName = toCodePointHexFileName(unicode);
        try (InputStream is = EmojiManager.class.getResourceAsStream("/emojis/" + hexName + ".png")) {
            if (is != null) {
                BufferedImage raw = ImageIO.read(is);
                if (raw != null) {
                    BufferedImage scaled = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2 = scaled.createGraphics();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.drawImage(raw, 0, 0, size, size, null);
                    g2.dispose();
                    return scaled;
                }
            }
        } catch (Exception ignored) {}

        // 2. Render trực tiếp bằng Bộ kết xuất Vector Đa Sắc (Đảm bảo 100% offline, sắc nét trên mọi OS)
        return EmojiArtRenderer.render(unicode, size);
    }

    private static String toCodePointHexFileName(String unicode) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < unicode.length()) {
            int cp = unicode.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xFE0F || cp == 0xFE0E) continue;
            if (sb.length() > 0) sb.append("-");
            sb.append(Integer.toHexString(cp).toLowerCase());
        }
        return sb.toString();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Phân tích văn bản & Hiển thị Emoji màu sắc trong khung hội thoại
    // ══════════════════════════════════════════════════════════════════════════

    public record TextSegment(boolean isEmoji, String content) {}

    /**
     * Tách chuỗi tin nhắn thành các đoạn văn bản thường và các Emoji.
     */
    public static List<TextSegment> tokenizeMessage(String text) {
        List<TextSegment> segments = new ArrayList<>();
        if (text == null || text.isEmpty()) return segments;

        StringBuilder plainBuffer = new StringBuilder();
        int i = 0;
        final int len = text.length();

        while (i < len) {
            String matchedEmoji = matchKnownEmojiAt(text, i);
            if (matchedEmoji != null) {
                if (plainBuffer.length() > 0) {
                    segments.add(new TextSegment(false, plainBuffer.toString()));
                    plainBuffer.setLength(0);
                }
                segments.add(new TextSegment(true, matchedEmoji));
                i += matchedEmoji.length();
                // Bỏ qua variation selector thừa ngay phía sau nếu có
                while (i < len) {
                    int nextCp = text.codePointAt(i);
                    if (nextCp == 0xFE0F || nextCp == 0xFE0E) {
                        i += Character.charCount(nextCp);
                    } else {
                        break;
                    }
                }
            } else {
                int cp = text.codePointAt(i);
                int charCount = Character.charCount(cp);
                // Phát hiện Emoji Unicode mở rộng ngoài danh mục chuẩn
                if (isGenericEmojiCodePoint(cp)) {
                    if (plainBuffer.length() > 0) {
                        segments.add(new TextSegment(false, plainBuffer.toString()));
                        plainBuffer.setLength(0);
                    }
                    StringBuilder cluster = new StringBuilder();
                    cluster.appendCodePoint(cp);
                    i += charCount;
                    while (i < len) {
                        int nextCp = text.codePointAt(i);
                        if (nextCp == 0xFE0F || nextCp == 0xFE0E || nextCp == 0x200D
                                || (nextCp >= 0x1F3FB && nextCp <= 0x1F3FF)) {
                            cluster.appendCodePoint(nextCp);
                            i += Character.charCount(nextCp);
                        } else {
                            break;
                        }
                    }
                    segments.add(new TextSegment(true, cluster.toString()));
                } else {
                    plainBuffer.appendCodePoint(cp);
                    i += charCount;
                }
            }
        }
        if (plainBuffer.length() > 0) {
            segments.add(new TextSegment(false, plainBuffer.toString()));
        }
        return segments;
    }

    /**
     * Kiểm tra tin nhắn có phải chỉ gồm từ 1-3 Emoji (để hiển thị Jumbo Emoji lớn).
     */
    public static boolean isJumboEmojiOnly(List<TextSegment> segments) {
        if (segments == null || segments.isEmpty()) return false;
        int emojiCount = 0;
        for (TextSegment seg : segments) {
            if (seg.isEmoji()) {
                emojiCount++;
            } else if (!seg.content().trim().isEmpty()) {
                return false;
            }
        }
        return emojiCount >= 1 && emojiCount <= 3;
    }

    /**
     * Đổ nội dung văn bản kết hợp hình ảnh Emoji đa sắc vào JTextPane.
     */
    public static void populateTextPaneWithColoredEmojis(JTextPane pane, String text, Font font, Color textColor, int emojiSize) {
        StyledDocument doc = pane.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
        } catch (BadLocationException ignored) {}

        SimpleAttributeSet textAttr = new SimpleAttributeSet();
        StyleConstants.setFontFamily(textAttr, font.getFamily());
        StyleConstants.setFontSize(textAttr, font.getSize());
        StyleConstants.setBold(textAttr, font.isBold());
        StyleConstants.setForeground(textAttr, textColor);

        List<TextSegment> segments = tokenizeMessage(text);
        for (TextSegment seg : segments) {
            try {
                if (seg.isEmoji()) {
                    ImageIcon icon = getEmojiIcon(seg.content(), emojiSize);
                    if (icon != null) {
                        SimpleAttributeSet iconAttr = new SimpleAttributeSet();
                        StyleConstants.setIcon(iconAttr, icon);
                        doc.insertString(doc.getLength(), seg.content(), iconAttr);
                    } else {
                        doc.insertString(doc.getLength(), seg.content(), textAttr);
                    }
                } else {
                    doc.insertString(doc.getLength(), seg.content(), textAttr);
                }
            } catch (BadLocationException ignored) {}
        }
    }

    private static String matchKnownEmojiAt(String text, int index) {
        for (String key : SORTED_UNICODES_DESC) {
            if (text.startsWith(key, index)) {
                return key;
            }
        }
        return null;
    }

    private static boolean isGenericEmojiCodePoint(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x1F1E6 && cp <= 0x1F1FF);
    }

    private static String stripVariationSelector(String s) {
        if (s == null) return "";
        return s.replace("\uFE0F", "").replace("\uFE0E", "");
    }

    public static String normalizeForSearch(String input) {
        if (input == null) return "";
        String lower = input.toLowerCase(Locale.ROOT).trim();
        String nfd = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return nfd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd');
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Bộ kết xuất đồ họa Vector Đa Sắc Nội Bộ (Offline Twemoji/Fluent Style)
    // ══════════════════════════════════════════════════════════════════════════

    private static class EmojiArtRenderer {

        static BufferedImage render(String unicode, int size) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = img.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            String clean = stripVariationSelector(unicode);

            // 1. Nhóm Cờ quốc gia (Vẽ vector chính xác 100% vì Windows không có font cờ)
            if (drawCountryFlag(g2, clean, size)) {
                g2.dispose();
                return img;
            }

            // 2. Nhóm Mặt cười & Cảm xúc (Vẽ khuôn mặt 3D Gradient vàng cam chuẩn Zalo/Messenger)
            if (drawExpressiveSmiley(g2, clean, size)) {
                g2.dispose();
                return img;
            }

            // 3. Nhóm Trái tim & Biểu tượng đặc trưng (Đổ màu Gradient rực rỡ)
            if (drawColorfulSymbol(g2, clean, size)) {
                g2.dispose();
                return img;
            }

            // 4. Các Emoji Đồ vật, Động vật, Đồ ăn, Cử chỉ tay:
            // Vẽ huy hiệu nền Pastel đa sắc hài hòa theo danh mục + Glyph kết hợp lớp phủ màu
            drawStyledBadgeEmoji(g2, unicode, clean, size);

            g2.dispose();
            return img;
        }

        private static boolean drawCountryFlag(Graphics2D g2, String clean, int s) {
            int pad = Math.max(1, s / 12);
            int w = s - pad * 2;
            int h = Math.max(10, (int) (w * 0.72));
            int x = pad;
            int y = (s - h) / 2;
            int arc = Math.max(4, s / 6);

            Shape clip = new RoundRectangle2D.Float(x, y, w, h, arc, arc);

            switch (clean) {
                case "🇻🇳" -> { // Việt Nam
                    g2.setClip(clip);
                    g2.setColor(new Color(0xDA251D));
                    g2.fillRect(x, y, w, h);
                    g2.setColor(new Color(0xFFFF00));
                    fillStar(g2, x + w / 2.0, y + h / 2.0, h * 0.30, h * 0.12);
                }
                case "🇯🇵" -> { // Nhật Bản
                    g2.setClip(clip);
                    g2.setColor(Color.WHITE);
                    g2.fillRect(x, y, w, h);
                    g2.setColor(new Color(0xBC002D));
                    int r = (int) (h * 0.30);
                    g2.fillOval(x + w / 2 - r, y + h / 2 - r, r * 2, r * 2);
                }
                case "🇰🇷" -> { // Hàn Quốc
                    g2.setClip(clip);
                    g2.setColor(Color.WHITE);
                    g2.fillRect(x, y, w, h);
                    int r = (int) (h * 0.26);
                    g2.setColor(new Color(0xCD2E3A));
                    g2.fillArc(x + w / 2 - r, y + h / 2 - r, r * 2, r * 2, 0, 180);
                    g2.setColor(new Color(0x0047A0));
                    g2.fillArc(x + w / 2 - r, y + h / 2 - r, r * 2, r * 2, 180, 180);
                }
                case "🇺🇸" -> { // Mỹ
                    g2.setClip(clip);
                    g2.setColor(Color.WHITE);
                    g2.fillRect(x, y, w, h);
                    g2.setColor(new Color(0xB22234));
                    int stripeH = Math.max(2, h / 7);
                    for (int i = 0; i < 7; i += 2) {
                        g2.fillRect(x, y + i * stripeH, w, stripeH);
                    }
                    g2.setColor(new Color(0x3C3B6E));
                    g2.fillRect(x, y, (int) (w * 0.45), (int) (h * 0.55));
                }
                case "🇬🇧" -> { // Anh
                    g2.setClip(clip);
                    g2.setColor(new Color(0x012169));
                    g2.fillRect(x, y, w, h);
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(Math.max(2f, s * 0.14f)));
                    g2.drawLine(x, y, x + w, y + h);
                    g2.drawLine(x + w, y, x, y + h);
                    g2.fillRect(x + w / 2 - 3, y, 6, h);
                    g2.fillRect(x, y + h / 2 - 3, w, 6);
                    g2.setColor(new Color(0xC8102E));
                    g2.fillRect(x + w / 2 - 2, y, 4, h);
                    g2.fillRect(x, y + h / 2 - 2, w, 4);
                }
                case "🇫🇷" -> { // Pháp
                    g2.setClip(clip);
                    g2.setColor(new Color(0x0055A4));
                    g2.fillRect(x, y, w / 3, h);
                    g2.setColor(Color.WHITE);
                    g2.fillRect(x + w / 3, y, w / 3, h);
                    g2.setColor(new Color(0xEF4135));
                    g2.fillRect(x + 2 * (w / 3), y, w - 2 * (w / 3), h);
                }
                case "🇩🇪" -> { // Đức
                    g2.setClip(clip);
                    g2.setColor(new Color(0x111111));
                    g2.fillRect(x, y, w, h / 3);
                    g2.setColor(new Color(0xDD0000));
                    g2.fillRect(x, y + h / 3, w, h / 3);
                    g2.setColor(new Color(0xFFCE00));
                    g2.fillRect(x, y + 2 * (h / 3), w, h - 2 * (h / 3));
                }
                case "🇨🇳" -> { // Trung Quốc
                    g2.setClip(clip);
                    g2.setColor(new Color(0xEE1C25));
                    g2.fillRect(x, y, w, h);
                    g2.setColor(new Color(0xFFFF00));
                    fillStar(g2, x + w * 0.25, y + h * 0.35, h * 0.20, h * 0.08);
                }
                case "🇹🇭" -> { // Thái Lan
                    g2.setClip(clip);
                    g2.setColor(new Color(0xA51931));
                    g2.fillRect(x, y, w, h);
                    g2.setColor(Color.WHITE);
                    g2.fillRect(x, y + h / 6, w, (int) (h * 4.0 / 6));
                    g2.setColor(new Color(0x2D2A4A));
                    g2.fillRect(x, y + h / 3, w, h / 3);
                }
                case "🇸🇬" -> { // Singapore
                    g2.setClip(clip);
                    g2.setColor(new Color(0xEF3340));
                    g2.fillRect(x, y, w, h / 2);
                    g2.setColor(Color.WHITE);
                    g2.fillRect(x, y + h / 2, w, h - h / 2);
                }
                case "🇦🇺", "🇨🇦" -> {
                    g2.setClip(clip);
                    if (clean.equals("🇨🇦")) {
                        g2.setColor(new Color(0xFF0000));
                        g2.fillRect(x, y, w, h);
                        g2.setColor(Color.WHITE);
                        g2.fillRect(x + w / 4, y, w / 2, h);
                        g2.setColor(new Color(0xFF0000));
                        fillStar(g2, x + w / 2.0, y + h / 2.0, h * 0.26, h * 0.11);
                    } else {
                        g2.setColor(new Color(0x00008B));
                        g2.fillRect(x, y, w, h);
                        g2.setColor(Color.WHITE);
                        fillStar(g2, x + w * 0.7, y + h * 0.5, h * 0.22, h * 0.09);
                    }
                }
                default -> {
                    return false;
                }
            }
            g2.setClip(null);
            g2.setColor(new Color(0, 0, 0, 45));
            g2.setStroke(new BasicStroke(1f));
            g2.draw(clip);
            return true;
        }

        private static boolean drawExpressiveSmiley(Graphics2D g2, String u, int s) {
            EmojiItem item = UNICODE_MAP.get(u);
            if (item == null || item.category() != Category.SMILEYS) return false;

            int pad = Math.max(1, s / 14);
            int d = s - pad * 2;

            // Màu nền khuôn mặt theo cảm xúc
            Color topCol = new Color(0xFFE259);
            Color botCol = new Color(0xFFA751);
            if (u.equals("😡") || u.equals("🤬") || u.equals("🥵")) {
                topCol = new Color(0xFF758C);
                botCol = new Color(0xFF4B2B);
            } else if (u.equals("🥶") || u.equals("😱") || u.equals("😨") || u.equals("😰")) {
                topCol = new Color(0x89F7FE);
                botCol = new Color(0x66A6FF);
            }

            GradientPaint faceGrad = new GradientPaint(pad, pad, topCol, pad, pad + d, botCol);
            g2.setPaint(faceGrad);
            g2.fillOval(pad, pad, d, d);

            // Viền ấm 3D quanh mặt
            g2.setColor(new Color(0, 0, 0, 35));
            g2.setStroke(new BasicStroke(Math.max(1f, s * 0.04f)));
            g2.drawOval(pad, pad, d, d);

            // Má hồng cho các mặt cười dễ thương
            if ("😊🥰😍😘🥳🥺😳🤗".contains(u)) {
                g2.setColor(new Color(0xFF5E7E, true));
                g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.45f));
                int bw = Math.max(4, s / 5);
                int bh = Math.max(3, s / 7);
                g2.fillOval((int) (s * 0.16), (int) (s * 0.52), bw, bh);
                g2.fillOval((int) (s * 0.64), (int) (s * 0.52), bw, bh);
                g2.setComposite(AlphaComposite.SrcOver);
            }

            // Vẽ các chi tiết đặc trưng nổi bật (kính râm, mắt tim, nước mắt, vòng thiên thần...)
            Color featureDark = new Color(0x3D2314);
            g2.setColor(featureDark);
            float strokeW = Math.max(1.5f, s * 0.075f);
            g2.setStroke(new BasicStroke(strokeW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            switch (u) {
                case "😍", "🥰" -> {
                    drawMiniHeart(g2, s * 0.33, s * 0.38, s * 0.22, new Color(0xE53935));
                    drawMiniHeart(g2, s * 0.67, s * 0.38, s * 0.22, new Color(0xE53935));
                    g2.setColor(featureDark);
                    g2.draw(new Arc2D.Double(s * 0.30, s * 0.48, s * 0.40, s * 0.26, 190, 160, Arc2D.OPEN));
                    if (u.equals("🥰")) {
                        drawMiniHeart(g2, s * 0.82, s * 0.22, s * 0.16, new Color(0xFF4081));
                    }
                    return true;
                }
                case "😎" -> {
                    // Kính râm đen cực ngầu
                    g2.setColor(new Color(0x1E293B));
                    g2.fillRoundRect((int) (s * 0.16), (int) (s * 0.30), (int) (s * 0.30), (int) (s * 0.20), 4, 4);
                    g2.fillRoundRect((int) (s * 0.54), (int) (s * 0.30), (int) (s * 0.30), (int) (s * 0.20), 4, 4);
                    g2.drawLine((int) (s * 0.12), (int) (s * 0.34), (int) (s * 0.88), (int) (s * 0.34));
                    g2.setColor(featureDark);
                    g2.draw(new Arc2D.Double(s * 0.32, s * 0.52, s * 0.36, s * 0.22, 195, 150, Arc2D.OPEN));
                    return true;
                }
                case "😂", "🤣" -> {
                    // Mắt cười híp + giọt nước mắt xanh
                    g2.draw(new Arc2D.Double(s * 0.22, s * 0.32, s * 0.20, s * 0.16, 0, 180, Arc2D.OPEN));
                    g2.draw(new Arc2D.Double(s * 0.58, s * 0.32, s * 0.20, s * 0.16, 0, 180, Arc2D.OPEN));
                    g2.setColor(new Color(0x991B1B));
                    g2.fill(new Arc2D.Double(s * 0.26, s * 0.44, s * 0.48, s * 0.34, 180, 180, Arc2D.CHORD));
                    g2.setColor(new Color(0x00B4D8));
                    g2.fillOval((int) (s * 0.12), (int) (s * 0.46), Math.max(4, s / 6), Math.max(5, s / 4));
                    g2.fillOval((int) (s * 0.74), (int) (s * 0.46), Math.max(4, s / 6), Math.max(5, s / 4));
                    return true;
                }
                case "😭", "😢" -> {
                    int er = Math.max(2, s / 11);
                    g2.fillOval((int) (s * 0.32) - er, (int) (s * 0.38) - er, er * 2, er * 2);
                    g2.fillOval((int) (s * 0.68) - er, (int) (s * 0.38) - er, er * 2, er * 2);
                    // Dòng nước mắt xanh
                    g2.setColor(new Color(0x38BDF8));
                    g2.fillRoundRect((int) (s * 0.27), (int) (s * 0.45), Math.max(3, s / 9), (int) (s * 0.34), 4, 4);
                    if (u.equals("😭")) {
                        g2.fillRoundRect((int) (s * 0.62), (int) (s * 0.45), Math.max(3, s / 9), (int) (s * 0.34), 4, 4);
                    }
                    g2.setColor(featureDark);
                    g2.draw(new Arc2D.Double(s * 0.32, s * 0.62, s * 0.36, s * 0.22, 15, 150, Arc2D.OPEN));
                    return true;
                }
                case "😘" -> {
                    g2.draw(new Arc2D.Double(s * 0.23, s * 0.34, s * 0.18, s * 0.14, 0, 180, Arc2D.OPEN));
                    int er = Math.max(2, s / 11);
                    g2.fillOval((int) (s * 0.65) - er, (int) (s * 0.38) - er, er * 2, er * 2);
                    g2.draw(new Arc2D.Double(s * 0.45, s * 0.56, s * 0.14, s * 0.16, -90, 180, Arc2D.OPEN));
                    drawMiniHeart(g2, s * 0.80, s * 0.66, s * 0.22, new Color(0xE53935));
                    return true;
                }
                case "😡", "😠", "🤬" -> {
                    // Lông mày giận dữ
                    g2.drawLine((int) (s * 0.22), (int) (s * 0.30), (int) (s * 0.42), (int) (s * 0.38));
                    g2.drawLine((int) (s * 0.78), (int) (s * 0.30), (int) (s * 0.58), (int) (s * 0.38));
                    int er = Math.max(2, s / 11);
                    g2.fillOval((int) (s * 0.33) - er, (int) (s * 0.43) - er, er * 2, er * 2);
                    g2.fillOval((int) (s * 0.67) - er, (int) (s * 0.43) - er, er * 2, er * 2);
                    g2.draw(new Arc2D.Double(s * 0.32, s * 0.62, s * 0.36, s * 0.20, 20, 140, Arc2D.OPEN));
                    return true;
                }
                default -> {
                    // Vẽ khuôn mặt từ glyph cảm xúc sắc nét trên nền tròn 3D vàng
                    drawCenteredGlyph(g2, u, s, new Color(0x3D2314), 0.74f);
                    return true;
                }
            }
        }

        private static boolean drawColorfulSymbol(Graphics2D g2, String clean, int s) {
            // Nhóm Trái tim đa sắc
            Map<String, Color> heartColors = Map.of(
                    "❤️", new Color(0xEF4444),
                    "❤", new Color(0xEF4444),
                    "🧡", new Color(0xF97316),
                    "💛", new Color(0xEAB308),
                    "💚", new Color(0x22C55E),
                    "💙", new Color(0x3B82F6),
                    "💜", new Color(0xA855F7),
                    "💖", new Color(0xEC4899),
                    "💘", new Color(0xF43F5E),
                    "💔", new Color(0xDC2626)
            );
            if (heartColors.containsKey(clean)) {
                Color c = heartColors.get(clean);
                drawMiniHeart(g2, s * 0.5, s * 0.52, s * 0.78, c);
                if (clean.equals("💖")) {
                    g2.setColor(new Color(0xFEF08A));
                    fillStar(g2, s * 0.74, s * 0.28, s * 0.16, s * 0.06);
                } else if (clean.equals("💔")) {
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(Math.max(1.5f, s * 0.07f)));
                    int[] zx = {(int) (s * 0.50), (int) (s * 0.43), (int) (s * 0.55), (int) (s * 0.48)};
                    int[] zy = {(int) (s * 0.24), (int) (s * 0.44), (int) (s * 0.56), (int) (s * 0.80)};
                    g2.drawPolyline(zx, zy, 4);
                }
                return true;
            }

            switch (clean) {
                case "🔥" -> {
                    drawMiniFlame(g2, s);
                    return true;
                }
                case "⭐", "✨" -> {
                    g2.setPaint(new GradientPaint(0, 0, new Color(0xFDE047), 0, s, new Color(0xF59E0B)));
                    fillStar(g2, s * 0.5, s * 0.5, s * 0.44, s * 0.19);
                    return true;
                }
                case "✅" -> {
                    int pad = Math.max(2, s / 10);
                    g2.setColor(new Color(0x22C55E));
                    g2.fillRoundRect(pad, pad, s - pad * 2, s - pad * 2, s / 3, s / 3);
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(Math.max(2f, s * 0.11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine((int) (s * 0.28), (int) (s * 0.52), (int) (s * 0.44), (int) (s * 0.68));
                    g2.drawLine((int) (s * 0.44), (int) (s * 0.68), (int) (s * 0.74), (int) (s * 0.34));
                    return true;
                }
                case "❌" -> {
                    int pad = Math.max(2, s / 10);
                    g2.setColor(new Color(0xEF4444));
                    g2.fillRoundRect(pad, pad, s - pad * 2, s - pad * 2, s / 3, s / 3);
                    g2.setColor(Color.WHITE);
                    g2.setStroke(new BasicStroke(Math.max(2f, s * 0.11f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine((int) (s * 0.32), (int) (s * 0.32), (int) (s * 0.68), (int) (s * 0.68));
                    g2.drawLine((int) (s * 0.68), (int) (s * 0.32), (int) (s * 0.32), (int) (s * 0.68));
                    return true;
                }
                case "💯" -> {
                    g2.setColor(new Color(0xE11D48));
                    g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, (int) (s * 0.52))));
                    FontMetrics fm = g2.getFontMetrics();
                    String str = "100";
                    int tx = (s - fm.stringWidth(str)) / 2;
                    int ty = (s - fm.getHeight()) / 2 + fm.getAscent() - 1;
                    g2.drawString(str, tx, ty);
                    g2.setStroke(new BasicStroke(Math.max(1.5f, s * 0.07f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawLine((int) (s * 0.18), (int) (s * 0.78), (int) (s * 0.82), (int) (s * 0.74));
                    g2.drawLine((int) (s * 0.22), (int) (s * 0.86), (int) (s * 0.78), (int) (s * 0.82));
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }

        private static void drawStyledBadgeEmoji(Graphics2D g2, String unicode, String clean, int s) {
            EmojiItem item = UNICODE_MAP.get(clean);
            Color baseColor = getCategoryThemeColor(item != null ? item.category() : Category.OBJECTS, clean);

            int pad = Math.max(1, s / 16);
            int d = s - pad * 2;

            // Vẽ đĩa nền Gradient mềm mại làm nổi bật màu sắc của từng Emoji
            Color lightBg = new Color(
                    Math.min(255, baseColor.getRed() + (255 - baseColor.getRed()) * 3 / 4),
                    Math.min(255, baseColor.getGreen() + (255 - baseColor.getGreen()) * 3 / 4),
                    Math.min(255, baseColor.getBlue() + (255 - baseColor.getBlue()) * 3 / 4)
            );
            Color midBg = new Color(
                    Math.min(255, baseColor.getRed() + (255 - baseColor.getRed()) / 2),
                    Math.min(255, baseColor.getGreen() + (255 - baseColor.getGreen()) / 2),
                    Math.min(255, baseColor.getBlue() + (255 - baseColor.getBlue()) / 2)
            );

            g2.setPaint(new GradientPaint(pad, pad, lightBg, pad, pad + d, midBg));
            g2.fillRoundRect(pad, pad, d, d, d / 2, d / 2);

            g2.setColor(new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), 110));
            g2.setStroke(new BasicStroke(1.1f));
            g2.drawRoundRect(pad, pad, d, d, d / 2, d / 2);

            Color glyphColor = new Color(
                    Math.max(0, (int) (baseColor.getRed() * 0.72)),
                    Math.max(0, (int) (baseColor.getGreen() * 0.72)),
                    Math.max(0, (int) (baseColor.getBlue() * 0.72))
            );
            drawCenteredGlyph(g2, unicode, s, glyphColor, 0.68f);
        }

        private static Color getCategoryThemeColor(Category cat, String clean) {
            int hash = Math.abs(clean.hashCode());
            return switch (cat) {
                case PEOPLE -> new Color(0xF59E0B);
                case ANIMALS -> (hash % 2 == 0) ? new Color(0x10B981) : new Color(0xF97316);
                case FOOD -> (hash % 2 == 0) ? new Color(0xEF4444) : new Color(0xF59E0B);
                case ACTIVITIES -> (hash % 2 == 0) ? new Color(0x3B82F6) : new Color(0x8B5CF6);
                case TRAVEL -> new Color(0x0EA5E9);
                case OBJECTS -> (hash % 2 == 0) ? new Color(0x6366F1) : new Color(0x14B8A6);
                case SYMBOLS -> new Color(0xEC4899);
                default -> new Color(0x0068FF);
            };
        }

        private static void drawCenteredGlyph(Graphics2D g2, String glyph, int s, Color color, float scale) {
            int fontSize = Math.max(11, Math.round(s * scale));
            Font font = new Font("Segoe UI Emoji", Font.PLAIN, fontSize);
            if (font.canDisplayUpTo(glyph) != -1) {
                font = new Font(Font.SANS_SERIF, Font.PLAIN, fontSize);
            }
            g2.setFont(font);
            g2.setColor(color);
            FontMetrics fm = g2.getFontMetrics();
            int w = fm.stringWidth(glyph);
            int x = (s - w) / 2;
            int y = (s - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(glyph, x, y);
        }

        private static void drawMiniHeart(Graphics2D g2, double cx, double cy, double size, Color color) {
            GeneralPath path = new GeneralPath();
            double topY = cy - size * 0.32;
            double botY = cy + size * 0.45;
            path.moveTo(cx, botY);
            path.curveTo(cx - size * 0.62, cy + size * 0.08, cx - size * 0.55, topY - size * 0.22, cx, topY + size * 0.12);
            path.curveTo(cx + size * 0.55, topY - size * 0.22, cx + size * 0.62, cy + size * 0.08, cx, botY);
            path.closePath();
            g2.setColor(color);
            g2.fill(path);
        }

        private static void drawMiniFlame(Graphics2D g2, int s) {
            GeneralPath outer = new GeneralPath();
            outer.moveTo(s * 0.50, s * 0.10);
            outer.curveTo(s * 0.82, s * 0.35, s * 0.86, s * 0.72, s * 0.50, s * 0.90);
            outer.curveTo(s * 0.14, s * 0.72, s * 0.18, s * 0.35, s * 0.50, s * 0.10);
            outer.closePath();
            g2.setPaint(new GradientPaint(0, 0, new Color(0xFF9800), 0, s, new Color(0xE53935)));
            g2.fill(outer);

            GeneralPath inner = new GeneralPath();
            inner.moveTo(s * 0.50, s * 0.38);
            inner.curveTo(s * 0.68, s * 0.54, s * 0.68, s * 0.76, s * 0.50, s * 0.86);
            inner.curveTo(s * 0.32, s * 0.76, s * 0.32, s * 0.54, s * 0.50, s * 0.38);
            inner.closePath();
            g2.setColor(new Color(0xFFEB3B));
            g2.fill(inner);
        }

        private static void fillStar(Graphics2D g2, double cx, double cy, double outerR, double innerR) {
            GeneralPath star = new GeneralPath();
            for (int i = 0; i < 10; i++) {
                double angle = Math.toRadians(-90 + i * 36);
                double r = (i % 2 == 0) ? outerR : innerR;
                double x = cx + Math.cos(angle) * r;
                double y = cy + Math.sin(angle) * r;
                if (i == 0) star.moveTo(x, y);
                else star.lineTo(x, y);
            }
            star.closePath();
            g2.fill(star);
        }
    }
}