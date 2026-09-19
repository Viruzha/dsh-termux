package site.viruzha.hub;

/** 小工具：地址推导、显示格式化。 */
public final class Ui {

    private Ui() { }

    /** https://host/wake -> https://host */
    public static String baseOf(String endpoint) {
        if (endpoint == null) return "";
        String e = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        int i = e.lastIndexOf('/');
        return i > 8 ? e.substring(0, i) : e;
    }

    /** 从地址里取出主机名，用于界面右上角的次要信息。 */
    public static String hostOf(String endpoint) {
        try {
            String b = baseOf(endpoint);
            int i = b.indexOf("://");
            return i >= 0 ? b.substring(i + 3) : b;
        } catch (Exception e) { return ""; }
    }

    public static String healthUrl(String endpoint) { return baseOf(endpoint) + "/health"; }

    public static String statusUrl(String endpoint, String token) {
        String sep = baseOf(endpoint).contains("?") ? "&" : "?";
        return baseOf(endpoint) + "/status" + sep + "token=" + token;
    }

    /** 极简的字段提取，避免为一个字段引入 JSON 解析器。 */
    public static boolean bodyHasTrue(String body, String key) {
        if (body == null) return false;
        return body.replace(" ", "").contains("\"" + key + "\":true");
    }
}
