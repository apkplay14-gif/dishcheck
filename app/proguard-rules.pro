# protobuf-java будує й розбирає повідомлення через рефлексію дескрипторів —
# без цього зчитування Starlink ламається.
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Власний код не чіпаємо: моделі серіалізуються в JSON історії за назвами полів,
# а мережевий розбір спирається на protobuf. Обфускуються лише сторонні бібліотеки.
-keep class ua.starlink.reader.** { *; }
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
