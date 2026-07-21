# Keen Zero branding pack

Source of truth for launcher icon and Android TV banner.

## Layout

```text
branding/
  masters/
    keen_zero_icon_master_1254.png
    keen_zero_tv_banner_master_1672x941.png
  app/src/main/res/          ← Android density pack (copy into app)
    drawable/ic_launcher_foreground.png
    drawable-xhdpi/tv_banner.png
    mipmap-{mdpi…xxxhdpi}/ic_launcher.png
    mipmap-{mdpi…xxxhdpi}/tv_banner.png
    mipmap-anydpi-v26/ic_launcher.xml
    values/colors.xml          # ic_launcher_background = #000000
```

## App wiring

| Manifest | Resource |
|----------|----------|
| `android:icon` | `@mipmap/ic_launcher` (adaptive on API 26+) |
| `android:banner` | `@mipmap/tv_banner` |

Product res lives under `app/src/main/res/` and must stay byte-identical to this pack for shipped densities.

## Refresh procedure

From project root:

```bash
SRC=branding/app/src/main/res
APP=app/src/main/res
for dens in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  cp -f "$SRC/mipmap-$dens/ic_launcher.png" "$APP/mipmap-$dens/"
  cp -f "$SRC/mipmap-$dens/tv_banner.png" "$APP/mipmap-$dens/"
  cp -f "$SRC/mipmap-$dens/tv_banner.png" "$APP/mipmap-$dens/banner.png"  # legacy alias
done
cp -f "$SRC/mipmap-anydpi-v26/ic_launcher.xml" "$APP/mipmap-anydpi-v26/"
cp -f "$SRC/drawable/ic_launcher_foreground.png" "$APP/drawable/"
mkdir -p "$APP/drawable-xhdpi"
cp -f "$SRC/drawable-xhdpi/tv_banner.png" "$APP/drawable-xhdpi/"
# values/ic_launcher_background.xml → #000000
```

Then rebuild release and reinstall on the box (launcher may cache icons until force-stop / reboot).
