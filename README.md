# Babyphone

Appli Android perso : un téléphone reste dans la chambre et écoute, l'autre sonne quand bébé fait du bruit. Micro uniquement, pas de caméra, pas de serveur ni de compte : les deux téléphones se parlent directement sur le Wi-Fi de la maison.

## Comment ça marche

- **Téléphone bébé** : écoute le micro en continu, écran éteint possible. Si le bruit dépasse le seuil pendant au moins 0,5 s (sur une fenêtre de 1,5 s), il envoie une alerte. Une alerte max toutes les 30 s.
- **Téléphone parent** : reste connecté sans garder le processeur éveillé. À l'alerte : son + vibration (même en mode silencieux), notification, et écoute en direct pendant 30 s (désactivable). Bouton « Écouter » pour écouter à tout moment.
- **Sécurité** : le téléphone bébé envoie un signe de vie toutes les 15 s. Si le parent n'a plus de nouvelles pendant 50 s, il sonne avec « Babyphone déconnecté ». Le parent voit aussi la batterie du téléphone bébé.

## Compiler l'APK (GitHub Actions)

1. Crée un dépôt GitHub (privé, c'est mieux) et envoie-y tout le contenu de ce dossier, y compris `.github/` et `app/debug.keystore`.
2. Chaque push sur `main` lance la compilation (onglet **Actions**, environ 3 à 5 min).
3. L'APK apparaît dans **Releases** sous le nom `babyphone.apk`. Il est aussi téléchargeable depuis le run, dans **Artifacts**.

Si la compilation échoue, ouvre le run en rouge, copie le message d'erreur et envoie-le moi.

## Installer sur les téléphones

1. Sur chaque téléphone, ouvre la page Releases du dépôt (connecté à GitHub si le dépôt est privé) et télécharge `babyphone.apk`.
2. Ouvre le fichier et autorise l'installation depuis le navigateur quand Android le demande.
3. Les mises à jour s'installent par-dessus, sans désinstaller (la clé de signature est fixe).

## Premier lancement

1. **Les deux téléphones sur le même Wi-Fi.**
2. Téléphone de la chambre : rôle **Bébé** → **Démarrer**, autorise le micro et les notifications. Branche-le sur secteur.
3. Ton téléphone : rôle **Parent** → **Démarrer**. Il trouve le téléphone bébé tout seul. Si ça ne marche pas (certaines box bloquent la découverte), recopie l'adresse IP affichée sur le téléphone bébé dans le champ prévu.
4. Sur **les deux** téléphones, touche **Autoriser le fonctionnement en arrière-plan**. Sans ça, Android peut couper l'appli écran éteint.
5. Fais un test : fais du bruit près du téléphone bébé et règle le seuil pour qu'il ne se déclenche pas sur le bruit de fond de la chambre.

## Limites

- Fonctionne seulement sur le même réseau Wi-Fi (pas en 4G/5G, pas hors de portée du Wi-Fi).
- Sur certaines marques (Xiaomi, Samsung, Huawei, Oppo…), il faut parfois aussi désactiver la « mise en veille » de l'appli dans les réglages batterie du téléphone.
- C'est un outil maison : fais un test chaque soir les premiers jours, et garde l'alerte « déconnecté » activée.
