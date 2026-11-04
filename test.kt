import java.util.Locale

fun main() {
    val latText = String.format(Locale.US, "%.4f", 51.5)
    val lonText = String.format(Locale.US, "%.4f", -0.1)
    val url = String.format(Locale.US, "https://api.adsb.lol/v2/point/%s/%s/%d", latText, lonText, 100)
    println(url)
}
