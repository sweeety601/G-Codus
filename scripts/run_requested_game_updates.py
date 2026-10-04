from pathlib import Path

main = Path('app/src/main/java/com/example/gcodus/MainActivity.kt').read_text()
char = Path('app/src/main/java/com/example/gcodus/CharacterDatabase.kt').read_text()
promo = Path('app/src/main/java/com/example/gcodus/PromoCodeSource.kt').read_text()
feed = Path('scripts/sync_banners.py').read_text()
icons = Path('scripts/sync_game_icons.py').read_text()

markers = [
    'HorizontalScrollView(this)',
    'endfieldRarityForDisplay',
    'ENDFIELD_6_STAR',
    'hoyo-codes.seria.moe/codes?game=hkrpg',
    'LEAK_SOURCES = {',
    'game_starrail.svg',
    'game_endfield.svg',
]
if all(m in main + char + promo + feed + icons for m in markers):
    print('Requested game updates are already applied.')
else:
    script = Path('scripts/apply_requested_game_updates.py').read_text()
    exec(compile(script, 'scripts/apply_requested_game_updates.py', 'exec'), {})
