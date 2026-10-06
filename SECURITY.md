# Безпека / Security

Знайшли вразливість у застосунку «Є Starlink»? Будь ласка, **не відкривайте
публічний issue** — напишіть на **apkplay14@gmail.com** або скористайтеся
кнопкою **Report a vulnerability** на вкладці Security цього репозиторію.
Опишіть, що саме не так і як це відтворити.

Found a vulnerability in the «Є Starlink» app? Please **do not open a public
issue** — email **apkplay14@gmail.com** or use **Report a vulnerability** on the
Security tab of this repository, with steps to reproduce.

Застосунок не має власних серверів: зчитані дані лишаються на телефоні.
Мережеві запити йдуть до тарілки й роутера Starlink у локальній мережі, до
speed.cloudflare.com (лише коли користувач сам запускає тест швидкості) і до
вбудованих сервісів Google: реклама AdMob, форма згоди, оцінка в Play, сканер
ML Kit.
