package com.grandmaster.draughts;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GameLogger est le cerveau de la mémoire de l'IA, avec un système de score.
 * Il apprend des victoires (+1), des défaites (-1) et des nulles (0) pour évaluer
 * la qualité de chaque coup et devenir plus intelligent à chaque partie.
 */
public class GameLogger {

    private static final String TAG = "GameLogger";
    private static final String BOOK_FILE_NAME = "grandmaster_scored_book.txt";
    // Structure de données sophistiquée : Map<Historique, Map<Coup, Score>>
    private final Map<String, Map<String, Integer>> openingBook = new HashMap<>();
    private final File bookFile;

    public GameLogger(Context context) {
        File storageDir = context.getExternalFilesDir(null);
        if (storageDir == null) {
            // Plan B si le stockage externe n'est pas accessible
            storageDir = context.getFilesDir();
        }
        this.bookFile = new File(storageDir, BOOK_FILE_NAME);
        loadOpeningBook();
    }

    /**
     * Charge le livre d'ouvertures à score depuis le fichier texte en mémoire.
     */
    private void loadOpeningBook() {
        if (!bookFile.exists()) {
            Log.d(TAG, "Le livre d'ouvertures à score n'existe pas. Il sera créé.");
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(bookFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                // Format: "historique_des_coups:coup1,score1;coup2,score2"
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    String historyKey = parts[0];
                    Map<String, Integer> movesWithScores = new HashMap<>();
                    String[] moveData = parts[1].split(";");
                    for (String moveEntry : moveData) {
                        String[] moveScore = moveEntry.split(",");
                        if (moveScore.length == 2) {
                            movesWithScores.put(moveScore[0], Integer.parseInt(moveScore[1]));
                        }
                    }
                    openingBook.put(historyKey, movesWithScores);
                }
            }
            Log.d(TAG, "Livre d'ouvertures à score chargé avec " + openingBook.size() + " positions connues.");
        } catch (IOException | NumberFormatException e) {
            Log.e(TAG, "Erreur critique lors du chargement du livre d'ouvertures.", e);
        }
    }

    /**
     * Méthode principale d'apprentissage.
     * Met à jour les scores des coups joués en fonction du résultat de la partie.
     * @param gameMoves La liste des coups de la partie.
     * @param outcome 1 si l'IA a gagné, -1 si l'IA a perdu, 0 pour une nulle.
     */
    public void learnFromGame(List<String> gameMoves, int outcome) {
        if (gameMoves.isEmpty()) {
            return;
        }

        String result = (outcome == 1) ? "VICTOIRE" : (outcome == -1 ? "DÉFAITE" : "NULLE");
        Log.d(TAG, "Apprentissage de la partie avec résultat: " + result);

        for (int i = 0; i < gameMoves.size(); i++) {
            // La clé est l'historique des coups jusqu'à un certain point
            String historyKey = (i == 0) ? "" : String.join(",", gameMoves.subList(0, i));
            String movePlayed = gameMoves.get(i);

            // Récupère ou crée la liste des coups pour cette position
            Map<String, Integer> moves = openingBook.computeIfAbsent(historyKey, k -> new HashMap<>());

            // Met à jour le score pour le coup qui a été joué
            int currentScore = moves.getOrDefault(movePlayed, 0);
            moves.put(movePlayed, currentScore + outcome);
        }

        // Sauvegarde l'intégralité du livre mis à jour dans le fichier
        saveOpeningBook();
    }

    /**
     * Sauvegarde la totalité de la base de connaissances dans le fichier.
     * Cette méthode est plus sûre car elle réécrit tout, évitant les corruptions.
     */
    private void saveOpeningBook() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(bookFile, false))) { // 'false' pour écraser
            for (Map.Entry<String, Map<String, Integer>> entry : openingBook.entrySet()) {
                String historyKey = entry.getKey();
                StringBuilder lineBuilder = new StringBuilder();
                lineBuilder.append(historyKey).append(":");

                List<String> moveEntries = new ArrayList<>();
                for (Map.Entry<String, Integer> moveData : entry.getValue().entrySet()) {
                    moveEntries.add(moveData.getKey() + "," + moveData.getValue());
                }
                lineBuilder.append(String.join(";", moveEntries));

                writer.write(lineBuilder.toString());
                writer.newLine();
            }
            Log.d(TAG, "Livre d'ouvertures à score sauvegardé avec succès.");
        } catch (IOException e) {
            Log.e(TAG, "Erreur critique lors de la sauvegarde du livre d'ouvertures.", e);
        }
    }

    /**
     * Trouve le meilleur coup pour la situation actuelle en se basant sur les scores passés.
     * @param currentHistory L'historique des coups de la partie en cours.
     * @return Le coup ayant le meilleur score, ou null si la position est inconnue ou peu fiable.
     */
    public String getBookMove(List<String> currentHistory) {
        String historyKey = String.join(",", currentHistory);
        Map<String, Integer> possibleMoves = openingBook.get(historyKey);

        if (possibleMoves == null || possibleMoves.isEmpty()) {
            return null; // Cette position est inconnue, l'IA devra réfléchir.
        }

        // Cherche le coup avec le score le plus élevé
        String bestMove = null;
        int bestScore = Integer.MIN_VALUE;

        for (Map.Entry<String, Integer> entry : possibleMoves.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                bestMove = entry.getKey();
            }
        }
        
        // Seuil de confiance : ne pas jouer un coup du livre s'il n'a pas fait ses preuves
        // et a mené à plus de défaites qu'à des victoires.
        if (bestScore < 1) {
             return null;
        }

        Log.d(TAG, "Coup du livre trouvé: " + bestMove + " (Score: " + bestScore + ")");
        return bestMove;
    }

    /**
     * Traduit une notation textuelle (ex: "11-15") en une séquence de coup pour le moteur.
     */
    public List<int[]> parseMoveNotation(String moveNotation) {
        if (moveNotation == null || moveNotation.isEmpty()) return null;
        try {
            String separator = moveNotation.contains("x") ? "x" : "-";
            String[] parts = moveNotation.split(separator);
            int startPos = Integer.parseInt(parts[0]);
            int endPos = Integer.parseInt(parts[1]);
            int startR = (startPos - 1) / 4;
            int startC = ((startPos - 1) % 4) * 2 + (startR % 2 == 0 ? 1 : 0);
            int endR = (endPos - 1) / 4;
            int endC = ((endPos - 1) % 4) * 2 + (endR % 2 == 0 ? 1 : 0);
            List<int[]> sequence = new ArrayList<>();
            sequence.add(new int[]{startR, startC});
            sequence.add(new int[]{endR, endC});
            return sequence;
        } catch (NumberFormatException e) {
            Log.e(TAG, "Impossible de parser la notation: " + moveNotation);
            return null;
        }
    }
              }
