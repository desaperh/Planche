# Planche — lecteur CBZ sans autorisation

Lecteur de BD/manga CBZ pour Android (pensé pour les BOOX e-ink).

**Autorisations demandées : aucune.** Le manifeste ne contient aucune balise `<uses-permission>` :
- pas d'accès au stockage : les fichiers passent par le sélecteur système d'Android (Storage Access Framework), qui donne accès uniquement au fichier ou au dossier que vous choisissez ;
- pas d'Internet : l'appli ne peut rien envoyer, même si elle le voulait ;
- aucune bibliothèque tierce, aucune pub, aucun traceur.

## Compiler

Il faut un JDK 17+ et un SDK Android **Linux** (WSL ou Zorin) :

```bash
sdkmanager "build-tools;35.0.0" "platforms;android-35"   # si besoin
export ANDROID_HOME=~/Android/Sdk                         # adapter
./build.sh             # → build/Planche.apk
./build.sh --install   # compile puis installe via adb
```

Le script n'utilise ni Gradle ni Android Studio (aapt2 → javac → d8 → zipalign → apksigner) et
affiche à la fin les autorisations de l'APK, ce qui permet de vérifier qu'il n'y en a aucune.
Il crée `planche.keystore` au premier lancement : gardez-le, sinon il faudra désinstaller
l'appli avant chaque mise à jour.

### Compilation par GitHub Actions

Chaque push sur `main` lance les tests et compile l'APK : il se télécharge dans l'onglet
**Actions** → dernier run → artefact `Planche-apk`. Un tag `v*` (ex. `git tag v1.1 && git push --tags`)
publie en plus l'APK dans une **Release**, téléchargeable directement depuis la tablette.

Pour que l'APK de la CI s'installe par-dessus celui compilé chez vous, donnez-lui la même clé
(le dépôt est public : la clé ne doit jamais y être commitée) :

```bash
base64 -w0 planche.keystore   # copier la sortie
```

puis **Settings → Secrets and variables → Actions → New repository secret**,
nom `PLANCHE_KEYSTORE_B64`. Sans ce secret, la CI signe avec une clé jetable.

## Utilisation

- **Choisir un dossier** : sélectionnez votre dossier de BD (pas la racine du stockage ni `Download` : Android l'interdit, prenez un sous-dossier). Navigation dans les sous-dossiers, progression affichée pour chaque album.
- **Ouvrir un fichier** : un CBZ isolé. Vous pouvez aussi faire « Ouvrir avec… » → Planche depuis le gestionnaire de fichiers.
- **Lecture** : tap à droite = page suivante, tap à gauche = page précédente, tap au centre = menu. Les balayages et les touches (volume, page, flèches, télécommande Bluetooth) marchent aussi.
- **Menu** : barre de pages, sens de lecture BD (→) ou manga (←), affichage page entière ou pleine largeur (en largeur, un tap fait d'abord défiler la page vers le bas), retour au début.
- **Récents** : en haut de la bibliothèque, les 40 derniers albums ouverts avec la miniature de leur première page et la progression. Un tap rouvre l'album à la bonne page ; un appui long le retire de la liste (le fichier n'est pas touché). Les miniatures sont créées à la première ouverture et rangées dans le stockage privé de l'appli.
- La page en cours est mémorisée pour chaque album.

## Formats

- Archives : CBZ / ZIP (stocké ou deflate, ZIP64, noms UTF-8 ou CP437).
- Images : JPEG, PNG, WebP, GIF, BMP, HEIF, AVIF (Android 12+).
- Non pris en charge : CBR (RAR), CB7 (7z), PDF. Un message l'indique clairement si le fichier en est un.

## Tests

`python3 test/run_tests.py` vérifie le lecteur ZIP sur la JVM : tri naturel, fichiers parasites
(`__MACOSX`, `._*`), gros fichiers deflate, ZIP64 (70 000 entrées), noms CP437, données préfixées,
descripteurs de données, compressions non gérées, faux CBZ (RAR/PDF), archives tronquées et vides.
