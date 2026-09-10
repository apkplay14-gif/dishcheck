/// Одна позиція меню «що зчитувати»: номер, іконка, назва, підказка.
/// Спільна для HomeScreen (CaptureMenuCard) і ReadingCard.
struct MenuItemInfo {
    let number: Int
    let icon: String
    let titleKey: String
    let hintKey: String
}

let menuKit = MenuItemInfo(number: 1, icon: AppIcon.box, titleKey: "menu_kit", hintKey: "hint_qr")
let menuDishSN = MenuItemInfo(number: 2, icon: AppIcon.dish, titleKey: "menu_dish_serial", hintKey: "hint_qr")
let menuModemSN = MenuItemInfo(number: 3, icon: AppIcon.router, titleKey: "menu_modem_serial", hintKey: "hint_qr")
let menuStarlinkId = MenuItemInfo(number: 4, icon: AppIcon.signal, titleKey: "menu_starlink_id", hintKey: "hint_from_dish")
let menuRouterId = MenuItemInfo(number: 5, icon: AppIcon.id, titleKey: "menu_router_id", hintKey: "hint_from_router")
let menuMac = MenuItemInfo(number: 6, icon: AppIcon.mac, titleKey: "menu_mac", hintKey: "hint_from_router")
