from pathlib import Path

main_path = Path('app/src/main/java/com/example/gcodus/MainActivity.kt')
main = main_path.read_text()
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

if not all(m in main + char + promo + feed + icons for m in markers):
    script = Path('scripts/apply_requested_game_updates.py').read_text()
    exec(compile(script, 'scripts/apply_requested_game_updates.py', 'exec'), {})
    main = main_path.read_text()

# Portrait files uploaded by the user use the technical suffix "_card".
# It must not become part of the character identity, otherwise Acheron and
# Acheron_card are rendered as two separate cards and the online duplicate
# check cannot suppress the empty entry.
card_marker = 'CARD_FILENAME_IDENTITY_FIX_V1'
if card_marker not in main:
    old = '        val base = file.substringBeforeLast(".")'
    new = '''        // CARD_FILENAME_IDENTITY_FIX_V1\n        // Ignore technical asset suffixes when resolving the character name.\n        val base = file.substringBeforeLast(".")\n            .removeSuffix("_card")\n            .removeSuffix("_full")'''
    if old not in main:
        raise SystemExit('Portrait identity anchor not found')
    main = main.replace(old, new, 1)
    main_path.write_text(main)
    print('Applied portrait filename identity fix.')
else:
    print('Portrait filename identity fix already applied.')
