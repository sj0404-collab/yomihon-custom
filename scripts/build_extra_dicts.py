import json
import zipfile
import os

dict_dir = "/home/user/yomihon/dictionaries"
os.makedirs(dict_dir, exist_ok=True)

# 1. Russian Monolingual / Explanatory Dictionary
ru_index = {
    "title": "Толковый словарь русского языка",
    "format": 3,
    "revision": "2026.08.15",
    "sequenced": True,
    "author": "Yomihon",
    "description": "Толковый словарь терминов и слов русского языка.",
    "attribution": "Yomihon Dictionary Collection",
    "sourceLanguage": "ru",
    "targetLanguage": "ru",
    "isUpdatable": False,
    "downloadUrl": "https://github.com/yomihon/yomihon/raw/main/dictionaries/Russian_Explanatory.zip"
}

ru_terms = [
    ["привет", "привет", "", "", 0, ["Приветствие, дружеское обращение при встрече."], 0, ""],
    ["мир", "мир", "", "", 0, ["Состояние спокойствия и согласия; Земной шар, вселенная."], 0, ""],
    ["язык", "язык", "", "", 0, ["Система знаков, используемая для общения и выражения мыслей."], 0, ""],
    ["словарь", "словарь", "", "", 0, ["Собрание слов с их объяснениями или переводом на другой язык."], 0, ""],
    ["книга", "книга", "", "", 0, ["Печатное или рукописное издание в виде сшитых листов."], 0, ""],
    ["знание", "знание", "", "", 0, ["Результат познания, проверенный практикой."], 0, ""],
    ["чтение", "чтение", "", "", 0, ["Восприятие и понимание прочитанного текста."], 0, ""]
]

ru_zip_path = os.path.join(dict_dir, "Russian_Explanatory.zip")
with zipfile.ZipFile(ru_zip_path, 'w', zipfile.ZIP_DEFLATED) as zf:
    zf.writestr("index.json", json.dumps(ru_index, ensure_ascii=False, indent=2))
    zf.writestr("term_bank_1.json", json.dumps(ru_terms, ensure_ascii=False, indent=2))

print(f"Created {ru_zip_path}")

# 2. Latin to Cyrillic Dictionary
lat_index = {
    "title": "Латиница в Кириллицу (Latin -> Cyrillic)",
    "format": 3,
    "revision": "2026.08.15",
    "sequenced": True,
    "author": "Yomihon",
    "description": "Словарь транслитерации и перевода латинских символов и терминов на кириллицу.",
    "attribution": "Yomihon Dictionary Collection",
    "sourceLanguage": "la",
    "targetLanguage": "ru",
    "isUpdatable": False,
    "downloadUrl": "https://github.com/yomihon/yomihon/raw/main/dictionaries/Latin_to_Cyrillic.zip"
}

lat_terms = [
    ["privet", "", "", "", 0, ["кириллица: **привет** (hello)"], 0, ""],
    ["mir", "", "", "", 0, ["кириллица: **мир** (world/peace)"], 0, ""],
    ["spasibo", "", "", "", 0, ["кириллица: **спасибо** (thank you)"], 0, ""],
    ["dobro", "", "", "", 0, ["кириллица: **добро** (good)"], 0, ""],
    ["rossiya", "", "", "", 0, ["кириллица: **Россия** (Russia)"], 0, ""],
    ["moskva", "", "", "", 0, ["кириллица: **Москва** (Moscow)"], 0, ""],
    ["manga", "", "", "", 0, ["кириллица: **манга** (manga)"], 0, ""],
    ["yomihon", "", "", "", 0, ["кириллица: **ёмихон** (Yomihon)"], 0, ""],
    ["a", "", "", "", 0, ["кириллица: **а**"], 0, ""],
    ["b", "", "", "", 0, ["кириллица: **б**"], 0, ""],
    ["v", "", "", "", 0, ["кириллица: **в**"], 0, ""],
    ["g", "", "", "", 0, ["кириллица: **г**"], 0, ""],
    ["d", "", "", "", 0, ["кириллица: **д**"], 0, ""],
    ["e", "", "", "", 0, ["кириллица: **е**"], 0, ""],
    ["zh", "", "", "", 0, ["кириллица: **ж**"], 0, ""],
    ["z", "", "", "", 0, ["кириллица: **з**"], 0, ""],
    ["i", "", "", "", 0, ["кириллица: **и**"], 0, ""],
    ["k", "", "", "", 0, ["кириллица: **к**"], 0, ""],
    ["l", "", "", "", 0, ["кириллица: **л**"], 0, ""],
    ["m", "", "", "", 0, ["кириллица: **м**"], 0, ""],
    ["n", "", "", "", 0, ["кириллица: **н**"], 0, ""],
    ["o", "", "", "", 0, ["кириллица: **о**"], 0, ""],
    ["p", "", "", "", 0, ["кириллица: **п**"], 0, ""],
    ["r", "", "", "", 0, ["кириллица: **р**"], 0, ""],
    ["s", "", "", "", 0, ["кириллица: **с**"], 0, ""],
    ["t", "", "", "", 0, ["кириллица: **т**"], 0, ""],
    ["u", "", "", "", 0, ["кириллица: **у**"], 0, ""],
    ["f", "", "", "", 0, ["кириллица: **ф**"], 0, ""],
    ["kh", "", "", "", 0, ["кириллица: **х**"], 0, ""],
    ["ts", "", "", "", 0, ["кириллица: **ц**"], 0, ""],
    ["ch", "", "", "", 0, ["кириллица: **ч**"], 0, ""],
    ["sh", "", "", "", 0, ["кириллица: **ш**"], 0, ""],
    ["shch", "", "", "", 0, ["кириллица: **щ**"], 0, ""],
    ["yu", "", "", "", 0, ["кириллица: **ю**"], 0, ""],
    ["ya", "", "", "", 0, ["кириллица: **я**"], 0, ""]
]

lat_zip_path = os.path.join(dict_dir, "Latin_to_Cyrillic.zip")
with zipfile.ZipFile(lat_zip_path, 'w', zipfile.ZIP_DEFLATED) as zf:
    zf.writestr("index.json", json.dumps(lat_index, ensure_ascii=False, indent=2))
    zf.writestr("term_bank_1.json", json.dumps(lat_terms, ensure_ascii=False, indent=2))

print(f"Created {lat_zip_path}")
