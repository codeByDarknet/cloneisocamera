# MyCam

Application appareil photo Android **100 % native** (Kotlin + SDK Android, aucune dépendance),
avec galerie intégrée et une interface inspirée du design *Liquid Glass* d'iOS 26.

Née d'un besoin concret : sur un Galaxy A21s dont la caméra avant est morte, l'appareil photo
Samsung refuse de s'ouvrir. MyCam détecte les capteurs défaillants et se rabat automatiquement sur
un capteur qui fonctionne, sans jamais planter.

## Fonctionnalités

**Appareil photo (Camera2)**
- Modes Photo et Vidéo (sélecteur en verre, glissement horizontal entre les modes)
- Objectifs : grand-angle principal, ultra grand-angle (`.5`), zoom numérique `2`, pincement pour zoomer
- Bascule avant/arrière, avec repli automatique si un capteur ne répond pas
- Mise au point et exposition au toucher, avec indicateur animé
- Flash (Auto / Oui / Non), torche en vidéo
- Formats 4:3, 16:9 et 1:1 ; retardateur 3 s / 10 s ; grille des tiers ; son de l'obturateur
- Vidéo jusqu'en 1080p30 (selon le capteur), avec chronomètre
- Orientation EXIF correcte quelle que soit la façon de tenir le téléphone
- Répond aux intentions `IMAGE_CAPTURE` / `VIDEO_CAPTURE` : « Prendre une photo » depuis WhatsApp,
  un navigateur, etc., avec l'écran Reprendre / Utiliser la photo

**Galerie et visionneuse**
- Grille de toutes les photos et vidéos du téléphone, niveaux Années / Mois / Tout, filtres
  (Tout / MyCam / Vidéos / Favoris), sélection multiple
- Onglet Collections comme dans Photos : Épinglées (Favoris, Vidéos, Captures d'écran), Albums
  (un par dossier du téléphone, MyCam en premier) et Types de médias (Vidéos, Panoramas, Captures
  et Enregistrements d'écran) ; chaque album s'ouvre dans sa propre grille
- Visionneuse plein écran : balayage entre les médias, zoom par pincement et double tapotement
- Glisser vers le bas pour fermer, comme dans Photos
- Lecture vidéo intégrée avec barre de progression
- Partager, supprimer (corbeille système), favori, fiche d'informations EXIF
  (appareil, ouverture, vitesse, ISO, focale)
- Retouche des photos façon iPhone : réglages (Auto, exposition, contraste, saturation…), filtres,
  recadrage avec formats, redressement, rotation et miroir ; enregistrée comme une copie qui garde
  la date de la photo d'origine

**Interface**
- Panneaux en verre dépoli qui floutent réellement le contenu derrière eux
- Animations à ressorts (physique masse-ressort, pas de courbes fixes)
- Retour haptique, mode bord à bord, textes en français et en anglais

## Pourquoi zéro dépendance

Pas d'AndroidX, pas de CameraX, pas de Glide, pas de Compose. Tout repose sur le SDK de la plateforme :

- APK minuscule et démarrage instantané ;
- aucun code tiers, donc rien à auditer ni à mettre à jour ;
- un contrôle total de Camera2, indispensable pour gérer les capteurs défaillants que CameraX
  masque derrière ses abstractions.

## Architecture

```
app/src/main/java/dev/mycam/
├── camera/     Camera2 : inventaire des capteurs, session, choix des tailles, enregistrement vidéo
├── media/      MediaStore : requêtes, sauvegarde, chargement d'images, actions (partage, corbeille…)
├── ui/
│   ├── glass/  Système Liquid Glass : GlassView, GlassBackdrop (flou), Spring, contrôles segmentés
│   ├── camera/ Écran de prise de vue et ses composants (déclencheur, zoom, réglages rapides…)
│   ├── gallery/Galerie : grille, niveaux Années / Mois, Collections et albums
│   ├── editor/ Retouche des photos (réglages, filtres, recadrage)
│   └── viewer/ Visionneuse plein écran (pager, zoom, lecteur vidéo, fiche d'infos)
└── util/       Petites extensions (dimensions, bord à bord, formatage, préférences, haptique)
```

Points clés :

- **`CameraInventory`** lit les caractéristiques de chaque capteur (focales, niveau matériel, flash)
  et en déduit les objectifs disponibles, dont le facteur réel de l'ultra grand-angle.
- **`CameraSession`** encapsule tout le cycle Camera2. Chaque ouverture porte un numéro de
  génération, pour ignorer les rappels d'une session périmée. Un chien de garde de 4 s détecte un
  capteur qui s'ouvre sans jamais produire d'image.
- **`GlassBackdrop`** capture la vue située sous le verre, la réduit, puis la floute sur le CPU.
  Les `GlassView` dessinent ensuite ce flou avec reflets et bordure lumineuse.
- Les photos sont enregistrées dans `DCIM/MyCam` via MediaStore, sans permission de stockage
  sur Android 10 et plus.

## Compiler et installer

Prérequis : JDK 17+ et le SDK Android (compileSdk 36).

```bash
./gradlew assembleDebug        # APK dans app/build/outputs/apk/debug/
./gradlew installDebug         # installe sur le téléphone connecté en adb
./gradlew lint                 # analyse statique
```

Configuration : minSdk 26 (Android 8.0), targetSdk 35, identifiant d'application `dev.mycam`.

## Notes sur le Galaxy A21s (SM-A217F)

Relevées avec `adb` sur Android 12, HAL caméra Exynos de niveau LIMITED :

| ID | Capteur                       | État                                    |
|----|-------------------------------|-----------------------------------------|
| 0  | Arrière principal             | OK (photo 4000×3000, vidéo 1080p30)     |
| 2  | Arrière ultra grand-angle     | OK (facteur ≈ 0,54)                     |
| 1  | Avant                         | En panne                                |
| 3  | Avant (second identifiant)    | En panne : même capteur physique que 1  |

Les identifiants 1 et 3 échouent tous deux dans le pilote (`exynos_v4l2_s_input fail`, puis
`endConfigure … Function not implemented (-38)`). **La panne est matérielle** (capteur ou nappe),
et aucune application ne peut la contourner. MyCam essaie 1, puis 3, puis revient sur l'arrière en
environ 1,5 s. Il affiche alors un message et grise le bouton de bascule.

**Astuce pour l'appareil photo Samsung** : *Paramètres → Applications → Appareil photo → Stockage →
Effacer les données*. Le lancement échouera toujours en mode selfie, mais s'il s'était bloqué sur la
caméra avant, il peut retrouver l'arrière.

## Licence

Distribué sous licence MIT. Voir [LICENSE](LICENSE).
