package com.grandmaster.draughts;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

public class GameView extends View {
    // --- Constantes du jeu ---
    private static final int BOARD_SIZE = 8;
    private static final int MIN_BOARD_MARGIN = 30;
    private static final int FRAME_PADDING = 10;
    private static final float PIECE_RADIUS_FACTOR = 2.4f;
    private static final int KING_TEXT_OFFSET_Y = 12;
    private static final int HINT_DOT_RADIUS = 10;
    private static final int BOT_MOVE_DELAY_MS = 30;
    private static final String COLOR_LIGHT_SQUARE = "#FFF8DC";
    private static final String COLOR_DARK_SQUARE = "#556B2F";
    private static final String COLOR_WHITE_PIECE = "#E8E8E8";
    private static final String COLOR_RED_PIECE = "#D9534F";
    private static final String COLOR_FRAME = "#3E2723";
    private static final int COLOR_KING_SYMBOL = Color.WHITE;
    private static final int COLOR_HINT = Color.argb(128, 0, 0, 0);
    private static final int COLOR_BACKGROUND = Color.WHITE;
    private static final int COLOR_BUTTON_TEXT = Color.WHITE;
    private static final int COLOR_BUTTON_BG = Color.DKGRAY;
    private static final int COLOR_SHADOW = Color.argb(90, 0, 0, 0);

    private Paint lightSquarePaint, darkSquarePaint, whitePiecePaint, redPiecePaint, kingTextPaint, hintPaint, framePaint, scoreTextPaint, buttonPaint, buttonTextPaint;
    private Paint shadowPaint;
    private int squareSize;
    private int offsetX, offsetY;
    private int[][] board = new int[BOARD_SIZE][BOARD_SIZE];
    private int selectedRow = -1, selectedCol = -1;
    private List<List<int[]>> validMoves = new ArrayList<>();
    private boolean isWhiteTurn = true;
    private boolean gameEnded = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Stack<GameState> moveHistory = new Stack<>();
    private boolean isBoardFlipped = false;
    private boolean aiStartsFirst = false;
    private RectF newGameButton, undoButton, rotateButton, switchSideButton, drawButton;
    private final GameLogger gameLogger;
    private final List<String> currentGameMoves = new ArrayList<>();
    private int botWins = 0;
    private int userWins = 0;
    private final long[][][] zobristTable = new long[BOARD_SIZE][BOARD_SIZE][5];

    // --- MOTEUR D'IA ---
    private static final int MAX_AI_SEARCH_DEPTH = 22;
    private static final long AI_THINKING_TIME_MS = 10000L;
    private static final int KING_VALUE = 7;
    private static final int CORE_THREADS = 4;
    private static final int MAX_TRANSPOSITION_TABLE_SIZE = 850000;
    private static final long AI_THREAD_STACK_SIZE = 2 * 1024 * 1024;

    private final Map<Long, TranspositionEntry> transpositionTable =
            Collections.synchronizedMap(new LinkedHashMap<Long, TranspositionEntry>(MAX_TRANSPOSITION_TABLE_SIZE, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, TranspositionEntry> eldest) {
                    return size() > MAX_TRANSPOSITION_TABLE_SIZE;
                }
            });
    private ExecutorService aiExecutor;
    private volatile Thread ponderThread = null;

    private static class GameState {
        final int[][] boardState;
        final boolean isWhiteTurnState;

        GameState(int[][] board, boolean isWhiteTurn) {
            this.boardState = new int[BOARD_SIZE][BOARD_SIZE];
            for (int i = 0; i < BOARD_SIZE; i++) {
                System.arraycopy(board[i], 0, this.boardState[i], 0, BOARD_SIZE);
            }
            this.isWhiteTurnState = isWhiteTurn;
        }
    }

    private static class TranspositionEntry {
        int score;
        int depth;
        int flag;
    }

    private static class MoveRecord {
        List<int[]> sequence;
        int pieceMoved;
        List<Integer> capturedPieces = new ArrayList<>();
        List<int[]> capturedCoords = new ArrayList<>();
        boolean wasPromoted;
    }

    public GameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initZobrist();
        initPaints();
        gameLogger = new GameLogger(context);
        initExecutor();
        resetBoard();
    }

    private void initExecutor() {
        if (aiExecutor == null || aiExecutor.isShutdown()) {
            aiExecutor = Executors.newFixedThreadPool(CORE_THREADS);
        }
    }

    private void initZobrist() {
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                for (int p = 0; p < 5; p++) {
                    zobristTable[r][c][p] = (long) (Math.random() * Long.MAX_VALUE);
                }
            }
        }
    }

    private void initPaints() {
        lightSquarePaint = new Paint();
        lightSquarePaint.setColor(Color.parseColor(COLOR_LIGHT_SQUARE));
        darkSquarePaint = new Paint();
        darkSquarePaint.setColor(Color.parseColor(COLOR_DARK_SQUARE));
        whitePiecePaint = new Paint();
        whitePiecePaint.setColor(Color.parseColor(COLOR_WHITE_PIECE));
        whitePiecePaint.setAntiAlias(true);
        redPiecePaint = new Paint();
        redPiecePaint.setColor(Color.parseColor(COLOR_RED_PIECE));
        redPiecePaint.setAntiAlias(true);
        kingTextPaint = new Paint();
        kingTextPaint.setColor(COLOR_KING_SYMBOL);
        kingTextPaint.setTextSize(40);
        kingTextPaint.setTextAlign(Paint.Align.CENTER);
        kingTextPaint.setAntiAlias(true);
        kingTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        hintPaint = new Paint();
        hintPaint.setColor(COLOR_HINT);
        hintPaint.setAntiAlias(true);
        framePaint = new Paint();
        framePaint.setColor(Color.parseColor(COLOR_FRAME));
        scoreTextPaint = new Paint();
        scoreTextPaint.setColor(Color.BLACK);
        scoreTextPaint.setTextSize(40f);
        scoreTextPaint.setTextAlign(Paint.Align.LEFT);
        scoreTextPaint.setAntiAlias(true);
        buttonPaint = new Paint();
        buttonPaint.setColor(COLOR_BUTTON_BG);
        buttonPaint.setStyle(Paint.Style.FILL);
        buttonPaint.setAntiAlias(true);
        buttonTextPaint = new Paint();
        buttonTextPaint.setColor(COLOR_BUTTON_TEXT);
        buttonTextPaint.setTextSize(35f);
        buttonTextPaint.setTextAlign(Paint.Align.CENTER);
        buttonTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        buttonTextPaint.setAntiAlias(true);
        shadowPaint = new Paint();
        shadowPaint.setColor(COLOR_SHADOW);
        shadowPaint.setAntiAlias(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int buttonWidth = w / 5 - 10;
        int buttonHeight = 100;
        int topMargin = 40;
        newGameButton = new RectF(5, topMargin, 5 + buttonWidth, topMargin + buttonHeight);
        rotateButton = new RectF(10 + buttonWidth, topMargin, 10 + 2 * buttonWidth, topMargin + buttonHeight);
        drawButton = new RectF(15 + 2 * buttonWidth, topMargin, 15 + 3 * buttonWidth, topMargin + buttonHeight);
        switchSideButton = new RectF(20 + 3 * buttonWidth, topMargin, 20 + 4 * buttonWidth, topMargin + buttonHeight);
        undoButton = new RectF(25 + 4 * buttonWidth, topMargin, 25 + 5 * buttonWidth, topMargin + buttonHeight);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        calculateBoardDimensions();
        drawBackgroundAndFrame(canvas);
        drawBoardAndPieces(canvas);
        drawValidMoveHints(canvas);
        drawScores(canvas);
        drawButtons(canvas);
    }

    private void calculateBoardDimensions() {
        int availableWidth = getWidth() - 2 * MIN_BOARD_MARGIN;
        int availableHeight = getHeight() - (int) newGameButton.bottom - 150;
        int boardDimension = Math.min(availableWidth, availableHeight);
        squareSize = boardDimension / BOARD_SIZE;
        offsetX = (getWidth() - boardDimension) / 2;
        offsetY = (getHeight() - boardDimension) / 2 + (int) newGameButton.bottom / 2;
    }

    private void drawBackgroundAndFrame(Canvas canvas) {
        canvas.drawColor(COLOR_BACKGROUND);
        canvas.drawRect(offsetX - FRAME_PADDING, offsetY - FRAME_PADDING,
                offsetX + BOARD_SIZE * squareSize + FRAME_PADDING,
                offsetY + BOARD_SIZE * squareSize + FRAME_PADDING, framePaint);
    }

    private void drawButtons(Canvas canvas) {
        canvas.drawRoundRect(newGameButton, 15, 15, buttonPaint);
        canvas.drawText("↻", newGameButton.centerX(), newGameButton.centerY() + 15, buttonTextPaint);
        canvas.drawRoundRect(rotateButton, 15, 15, buttonPaint);
        canvas.drawText("⇄", rotateButton.centerX(), rotateButton.centerY() + 15, buttonTextPaint);
        canvas.drawRoundRect(drawButton, 15, 15, buttonPaint);
        canvas.drawText("=", drawButton.centerX(), drawButton.centerY() + 15, buttonTextPaint);
        canvas.drawRoundRect(switchSideButton, 15, 15, buttonPaint);
        canvas.drawText(aiStartsFirst ? "O" : "X", switchSideButton.centerX(), switchSideButton.centerY() + 15, buttonTextPaint);
        canvas.drawRoundRect(undoButton, 15, 15, buttonPaint);
        canvas.drawText("↩", undoButton.centerX(), undoButton.centerY() + 15, buttonTextPaint);
    }

    private void drawBoardAndPieces(Canvas canvas) {
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                int displayR = isBoardFlipped ? BOARD_SIZE - 1 - r : r;
                int displayC = isBoardFlipped ? BOARD_SIZE - 1 - c : c;
                Paint paint = ((r + c) % 2 == 0) ? lightSquarePaint : darkSquarePaint;
                canvas.drawRect(offsetX + displayC * squareSize, offsetY + displayR * squareSize,
                        offsetX + (displayC + 1) * squareSize, offsetY + (displayR + 1) * squareSize, paint);
                int piece = board[r][c];
                if (piece != 0) {
                    float cx = offsetX + displayC * squareSize + squareSize / 2f;
                    float cy = offsetY + displayR * squareSize + squareSize / 2f;
                    float radius = squareSize / PIECE_RADIUS_FACTOR;
                    float shadowOffset = radius / 12f;
                    canvas.drawCircle(cx + shadowOffset, cy + shadowOffset, radius, shadowPaint);
                    Paint piecePaint = isWhitePiece(piece) ? whitePiecePaint : redPiecePaint;
                    RadialGradient gradient = new RadialGradient(cx - radius / 3, cy - radius / 3, radius * 1.5f,
                            Color.argb(50, 255, 255, 255), piecePaint.getColor(),
                            Shader.TileMode.CLAMP);
                    Paint paintWithGradient = new Paint(piecePaint);
                    paintWithGradient.setShader(gradient);
                    canvas.drawCircle(cx, cy, radius, paintWithGradient);
                    Paint borderPaint = new Paint();
                    borderPaint.setStyle(Paint.Style.STROKE);
                    borderPaint.setStrokeWidth(2);
                    borderPaint.setColor(Color.argb(100, 0, 0, 0));
                    borderPaint.setAntiAlias(true);
                    canvas.drawCircle(cx, cy, radius, borderPaint);
                    if (isKing(piece)) {
                        canvas.drawText("♕", cx, cy + KING_TEXT_OFFSET_Y, kingTextPaint);
                    }
                }
            }
        }
    }

    private void drawValidMoveHints(Canvas canvas) {
        hintPaint.setStyle(Paint.Style.FILL);
        for (List<int[]> moveSequence : validMoves) {
            int[] move = moveSequence.get(moveSequence.size() - 1);
            int r = move[0];
            int c = move[1];
            int displayR = isBoardFlipped ? BOARD_SIZE - 1 - r : r;
            int displayC = isBoardFlipped ? BOARD_SIZE - 1 - c : c;
            float centerX = offsetX + displayC * squareSize + squareSize / 2f;
            float centerY = offsetY + displayR * squareSize + squareSize / 2f;
            canvas.drawCircle(centerX, centerY, HINT_DOT_RADIUS * 1.5f, hintPaint);
        }
    }

    private void drawScores(Canvas canvas) {
        float yPos = offsetY + BOARD_SIZE * squareSize + FRAME_PADDING + 60;
        scoreTextPaint.setColor(Color.parseColor(COLOR_RED_PIECE));
        canvas.drawText("BOT: " + botWins, MIN_BOARD_MARGIN, yPos, scoreTextPaint);
        scoreTextPaint.setColor(Color.BLACK);
        float userScoreX = getWidth() - MIN_BOARD_MARGIN;
        scoreTextPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("VOUS: " + userWins, userScoreX, yPos, scoreTextPaint);
        scoreTextPaint.setTextAlign(Paint.Align.LEFT);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN) return true;
        float x = event.getX();
        float y = event.getY();
        if (newGameButton.contains(x, y)) {
            resetBoard();
            return true;
        }
        if (rotateButton.contains(x, y)) {
            isBoardFlipped = !isBoardFlipped;
            invalidate();
            return true;
        }
        if (drawButton.contains(x, y)) {
            endGameAsDraw();
            return true;
        }
        if (switchSideButton.contains(x, y)) {
            aiStartsFirst = !aiStartsFirst;
            resetBoard();
            return true;
        }
        if (undoButton.contains(x, y)) {
            undoMove();
            return true;
        }
        if (gameEnded || !isWhiteTurn) return true;
        int col = (int) ((x - offsetX) / squareSize);
        int row = (int) ((y - offsetY) / squareSize);
        if (isBoardFlipped) {
            row = BOARD_SIZE - 1 - row;
            col = BOARD_SIZE - 1 - col;
        }
        if (!inBounds(row, col)) return true;
        handlePlayerMove(row, col);
        invalidate();
        return true;
    }

    private void resetBoard() {
        stopPondering();
        initExecutor();
        board = new int[BOARD_SIZE][BOARD_SIZE];
        for (int row = 0; row < BOARD_SIZE; row++) {
            for (int col = 0; col < BOARD_SIZE; col++) {
                if ((row + col) % 2 != 0) {
                    if (row < 3) board[row][col] = 2;
                    else if (row > 4) board[row][col] = 1;
                }
            }
        }
        resetSelectionAndMoves();
        isWhiteTurn = !aiStartsFirst;
        gameEnded = false;
        moveHistory.clear();
        transpositionTable.clear();
        currentGameMoves.clear();
        saveState();
        invalidate();
        if (aiStartsFirst && !isWhiteTurn) {
            handler.postDelayed(this::triggerBotMove, BOT_MOVE_DELAY_MS * 5);
        } else if (isWhiteTurn) {
            startPondering();
        }
    }

    private void handlePlayerMove(int row, int col) {
        stopPondering();
        if (selectedRow == -1) {
            if (isPlayerPiece(board[row][col], isWhiteTurn)) {
                List<List<int[]>> allPlayerMoves = getAllPossibleMovesForPlayer(board, isWhiteTurn);
                if (allPlayerMoves.isEmpty()) return;
                boolean pieceHasValidMove = false;
                for (List<int[]> sequence : allPlayerMoves) {
                    if (sequence.get(0)[0] == row && sequence.get(0)[1] == col) {
                        pieceHasValidMove = true;
                        break;
                    }
                }
                if (pieceHasValidMove) {
                    selectedRow = row;
                    selectedCol = col;
                    validMoves.clear();
                    for (List<int[]> sequence : allPlayerMoves) {
                        if (sequence.get(0)[0] == row && sequence.get(0)[1] == col) {
                            validMoves.add(sequence);
                        }
                    }
                } else if (!getAllCaptureMovesForPlayer(board, isWhiteTurn).isEmpty()) {
                    Toast.makeText(getContext(), "Capture obligatoire.", Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            boolean moveMade = false;
            for (List<int[]> sequence : validMoves) {
                int lastStepIndex = sequence.size() - 1;
                if (sequence.get(lastStepIndex)[0] == row && sequence.get(lastStepIndex)[1] == col) {
                    executeUserMove(sequence);
                    moveMade = true;
                    break;
                }
            }
            if (!moveMade) {
                resetSelectionAndMoves();
                handlePlayerMove(row, col);
            } else {
                changeTurn();
            }
        }
    }

    private void executeUserMove(List<int[]> sequence) {
        saveState();
        String moveNotation = getMoveNotation(sequence);
        currentGameMoves.add(moveNotation);
        applyMove(board, sequence);
    }

    private void saveState() {
        moveHistory.push(new GameState(board, isWhiteTurn));
    }

    private void undoMove() {
        stopPondering();
        if (moveHistory.size() > 1) {
            moveHistory.pop();
            restoreState(moveHistory.peek());
            gameEnded = false;
            Toast.makeText(getContext(), "Coup annulé.", Toast.LENGTH_SHORT).show();
            if (!currentGameMoves.isEmpty()) {
                currentGameMoves.remove(currentGameMoves.size() - 1);
            }
            if (!isWhiteTurn && moveHistory.size() > 1) {
                moveHistory.pop();
                restoreState(moveHistory.peek());
                if (!currentGameMoves.isEmpty()) {
                    currentGameMoves.remove(currentGameMoves.size() - 1);
                }
            }
            if (isWhiteTurn) startPondering();
        } else {
            Toast.makeText(getContext(), "Impossible d'annuler.", Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreState(GameState state) {
        for (int i = 0; i < BOARD_SIZE; i++) {
            System.arraycopy(state.boardState[i], 0, this.board[i], 0, BOARD_SIZE);
        }
        this.isWhiteTurn = state.isWhiteTurnState;
        transpositionTable.clear();
        resetSelectionAndMoves();
        invalidate();
    }

    private String getMoveNotation(List<int[]> sequence) {
        if (sequence == null || sequence.isEmpty()) return "";
        int startR = sequence.get(0)[0];
        int startC = sequence.get(0)[1];
        int endR = sequence.get(sequence.size() - 1)[0];
        int endC = sequence.get(sequence.size() - 1)[1];
        char separator = (countCapturesInSequence(sequence) > 0) ? 'x' : '-';
        return "" + (startR * 4 + startC / 2 + 1) + separator + (endR * 4 + endC / 2 + 1);
    }

    private MoveRecord applyMove(int[][] currentBoard, List<int[]> sequence) {
        MoveRecord record = new MoveRecord();
        record.sequence = sequence;
        int startR = sequence.get(0)[0];
        int startC = sequence.get(0)[1];
        int piece = currentBoard[startR][startC];
        record.pieceMoved = piece;

        currentBoard[startR][startC] = 0;

        int lastR = startR, lastC = startC;
        for (int i = 1; i < sequence.size(); i++) {
            int nextR = sequence.get(i)[0];
            int nextC = sequence.get(i)[1];
            if (Math.abs(lastR - nextR) >= 2) {
                int capturedR = (lastR + nextR) / 2;
                int capturedC = (lastC + nextC) / 2;
                record.capturedPieces.add(currentBoard[capturedR][capturedC]);
                record.capturedCoords.add(new int[]{capturedR, capturedC});
                currentBoard[capturedR][capturedC] = 0;
            }
            lastR = nextR;
            lastC = nextC;
        }

        int finalR = sequence.get(sequence.size() - 1)[0];
        int finalC = sequence.get(sequence.size() - 1)[1];
        boolean becomesKing = !isKing(piece) && ((isWhitePiece(piece) && finalR == 0) || (isBlackPiece(piece) && finalR == BOARD_SIZE - 1));
        record.wasPromoted = becomesKing;
        currentBoard[finalR][finalC] = becomesKing ? (isWhitePiece(piece) ? 3 : 4) : piece;
        return record;
    }

    private void undoMove(int[][] currentBoard, MoveRecord record) {
        List<int[]> sequence = record.sequence;
        int startR = sequence.get(0)[0];
        int startC = sequence.get(0)[1];
        int finalR = sequence.get(sequence.size() - 1)[0];
        int finalC = sequence.get(sequence.size() - 1)[1];

        currentBoard[startR][startC] = record.pieceMoved;
        currentBoard[finalR][finalC] = 0;

        for (int i = 0; i < record.capturedPieces.size(); i++) {
            int r = record.capturedCoords.get(i)[0];
            int c = record.capturedCoords.get(i)[1];
            currentBoard[r][c] = record.capturedPieces.get(i);
        }
    }

    private void changeTurn() {
        resetSelectionAndMoves();
        isWhiteTurn = !isWhiteTurn;
        invalidate();
        int status = checkGameStatus(board);
        if (status != 0) {
            endGame(status);
            return;
        }
        if (isWhiteTurn) {
            startPondering();
        } else if (!gameEnded) {
            Toast.makeText(getContext(), "L'IA réfléchit...", Toast.LENGTH_SHORT).show();
            handler.postDelayed(this::triggerBotMove, BOT_MOVE_DELAY_MS);
        }
    }

    private void resetSelectionAndMoves() {
        selectedRow = -1;
        selectedCol = -1;
        validMoves.clear();
    }

    private void endGameAsDraw() {
        stopPondering();
        gameEnded = true;
        Toast.makeText(getContext(), "Partie nulle par accord.", Toast.LENGTH_LONG).show();
        gameLogger.learnFromGame(currentGameMoves, 0);
        invalidate();
    }

    private void endGame(int winner) {
        stopPondering();
        gameEnded = true;
        String message;
        if (winner == 1) {
            message = "Vous avez gagné !";
            userWins++;
            gameLogger.learnFromGame(currentGameMoves, -1);
        } else {
            message = "Le bot a gagné !";
            botWins++;
            gameLogger.learnFromGame(currentGameMoves, 1);
        }
        Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
        invalidate();
    }

    private void startPondering() {
        stopPondering();
        initExecutor();
        Runnable ponderRunnable = () -> {
            try {
                findBestMove(board, Long.MAX_VALUE);
            } catch (Exception e) {
                // Thread interrupted is normal.
            }
        };
        ponderThread = new Thread(null, ponderRunnable, "Ponder_Thread", AI_THREAD_STACK_SIZE);
        ponderThread.start();
    }

    private void stopPondering() {
        if (ponderThread != null && ponderThread.isAlive()) {
            ponderThread.interrupt();
            try {
                ponderThread.join(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Log.d("PONDER", "Pondering arrêté.");
        }
        ponderThread = null;
    }

    private void triggerBotMove() {
        Runnable botMoveRunnable = () -> {
            final List<List<int[]>> allBestMoves = findBestMove(this.board, AI_THINKING_TIME_MS);
            handler.post(() -> {
                if (allBestMoves != null && !allBestMoves.isEmpty()) {
                    List<int[]> move = allBestMoves.get((int) (Math.random() * allBestMoves.size()));
                    saveState();
                    String moveNotation = getMoveNotation(move);
                    currentGameMoves.add(moveNotation);
                    applyMove(board, move);
                    changeTurn();
                } else {
                    int status = checkGameStatus(board);
                    if (status != 0) endGame(status);
                }
            });
        };
        new Thread(null, botMoveRunnable, "AI_Search_Thread", AI_THREAD_STACK_SIZE).start();
    }

    private List<List<int[]>> findBestMove(int[][] currentBoard, long timeLimit) {
        long startTime = System.currentTimeMillis();
        initExecutor();
        String bookMoveNotation = gameLogger.getBookMove(currentGameMoves);
        if (bookMoveNotation != null) {
            List<int[]> move = gameLogger.parseMoveNotation(bookMoveNotation);
            if (move != null) {
                List<List<int[]>> allMoves = getAllPossibleMovesForPlayer(currentBoard, false);
                for (List<int[]> validMove : allMoves) {
                    if (validMove.get(0)[0] == move.get(0)[0] && validMove.get(0)[1] == move.get(0)[1] &&
                            validMove.get(validMove.size() - 1)[0] == move.get(move.size() - 1)[0] &&
                            validMove.get(validMove.size() - 1)[1] == move.get(move.size() - 1)[1]) {
                        return Collections.singletonList(validMove);
                    }
                }
            }
        }
        List<List<int[]>> allPossibleMoves = getAllPossibleMovesForPlayer(currentBoard, false);
        if (allPossibleMoves.isEmpty()) return null;
        if (allPossibleMoves.size() == 1) return allPossibleMoves;
        List<List<int[]>> bestMovesSoFar = new ArrayList<>(Collections.singletonList(allPossibleMoves.get(0)));
        for (int depth = 1; depth <= MAX_AI_SEARCH_DEPTH; depth++) {
            if (System.currentTimeMillis() - startTime > timeLimit) break;
            List<Future<Integer>> results = new ArrayList<>();
            List<List<int[]>> movesForThisDepth = new ArrayList<>();
            final int currentDepth = depth;
            for (final List<int[]> sequence : allPossibleMoves) {
                Callable<Integer> task = () -> {
                    MoveRecord move = applyMove(currentBoard, sequence);
                    int score = minimax(currentBoard, currentDepth - 1, Integer.MIN_VALUE, Integer.MAX_VALUE, false, startTime + timeLimit);
                    undoMove(currentBoard, move);
                    return score;
                };
                try {
                    results.add(aiExecutor.submit(task));
                    movesForThisDepth.add(sequence);
                } catch (RejectedExecutionException e) {
                    return bestMovesSoFar;
                }
            }
            int bestScoreForThisDepth = Integer.MIN_VALUE;
            List<List<int[]>> bestMovesForThisDepth = new ArrayList<>();
            try {
                for (int i = 0; i < results.size(); i++) {
                    if (System.currentTimeMillis() - startTime > timeLimit) {
                        for (Future<Integer> future : results) {
                            future.cancel(true);
                        }
                        return bestMovesSoFar;
                    }
                    int score = results.get(i).get();
                    if (score > bestScoreForThisDepth) {
                        bestScoreForThisDepth = score;
                        bestMovesForThisDepth.clear();
                        bestMovesForThisDepth.add(movesForThisDepth.get(i));
                    } else if (score == bestScoreForThisDepth) {
                        bestMovesForThisDepth.add(movesForThisDepth.get(i));
                    }
                }
            } catch (Exception e) {
                return bestMovesSoFar;
            }
            bestMovesSoFar = bestMovesForThisDepth;
        }
        return bestMovesSoFar;
    }

    private int minimax(int[][] currentBoard, int depth, int alpha, int beta, boolean isMaximizingPlayer, long timeLimit) {
        if (System.currentTimeMillis() > timeLimit || Thread.currentThread().isInterrupted()) return 0;
        long boardHash = getBoardHash(currentBoard);
        TranspositionEntry entry = transpositionTable.get(boardHash);
        if (entry != null && entry.depth >= depth) {
            if (entry.flag == 0) return entry.score;
            if (entry.flag == 1) alpha = Math.max(alpha, entry.score);
            else if (entry.flag == 2) beta = Math.min(beta, entry.score);
            if (alpha >= beta) return entry.score;
        }
        int status = checkGameStatus(currentBoard);
        if (status != 0) return evaluateBoard(currentBoard, status);
        if (depth == 0) return quiescenceSearch(currentBoard, alpha, beta, isMaximizingPlayer, timeLimit);
        List<List<int[]>> allMoves = getAllPossibleMovesForPlayer(currentBoard, !isMaximizingPlayer);
        int originalAlpha = alpha;
        int originalBeta = beta;
        int bestScore;
        if (isMaximizingPlayer) {
            bestScore = Integer.MIN_VALUE;
            for (List<int[]> sequence : allMoves) {
                MoveRecord move = applyMove(currentBoard, sequence);
                int eval = minimax(currentBoard, depth - 1, alpha, beta, false, timeLimit);
                undoMove(currentBoard, move);
                if (System.currentTimeMillis() > timeLimit || Thread.currentThread().isInterrupted()) return 0;
                bestScore = Math.max(bestScore, eval);
                alpha = Math.max(alpha, eval);
                if (beta <= alpha) break;
            }
        } else {
            bestScore = Integer.MAX_VALUE;
            for (List<int[]> sequence : allMoves) {
                MoveRecord move = applyMove(currentBoard, sequence);
                int eval = minimax(currentBoard, depth - 1, alpha, beta, true, timeLimit);
                undoMove(currentBoard, move);
                if (System.currentTimeMillis() > timeLimit || Thread.currentThread().isInterrupted()) return 0;
                bestScore = Math.min(bestScore, eval);
                beta = Math.min(beta, eval);
                if (beta <= alpha) break;
            }
        }
        TranspositionEntry newEntry = new TranspositionEntry();
        newEntry.score = bestScore;
        newEntry.depth = depth;
        if (bestScore <= originalAlpha) newEntry.flag = 2;
        else if (bestScore >= originalBeta) newEntry.flag = 1;
        else newEntry.flag = 0;
        transpositionTable.put(boardHash, newEntry);
        return bestScore;
    }

    private int quiescenceSearch(int[][] currentBoard, int alpha, int beta, boolean isMaximizingPlayer, long timeLimit) {
        if (System.currentTimeMillis() > timeLimit || Thread.currentThread().isInterrupted()) return 0;
        int standPatScore = evaluateBoard(currentBoard, 0);
        if (isMaximizingPlayer) {
            alpha = Math.max(alpha, standPatScore);
            List<List<int[]>> captureMoves = getAllCaptureMovesForPlayer(currentBoard, false);
            if (captureMoves.isEmpty()) return standPatScore;
            for (List<int[]> sequence : captureMoves) {
                MoveRecord move = applyMove(currentBoard, sequence);
                int score = quiescenceSearch(currentBoard, alpha, beta, false, timeLimit);
                undoMove(currentBoard, move);
                alpha = Math.max(alpha, score);
                if (alpha >= beta) break;
            }
            return alpha;
        } else {
            beta = Math.min(beta, standPatScore);
            List<List<int[]>> captureMoves = getAllCaptureMovesForPlayer(currentBoard, true);
            if (captureMoves.isEmpty()) return standPatScore;
            for (List<int[]> sequence : captureMoves) {
                MoveRecord move = applyMove(currentBoard, sequence);
                int score = quiescenceSearch(currentBoard, alpha, beta, true, timeLimit);
                undoMove(currentBoard, move);
                beta = Math.min(beta, score);
                if (alpha >= beta) break;
            }
            return beta;
        }
    }

    private int evaluateBoard(int[][] currentBoard, int status) {
        if (status == 1) return -100000;
        if (status == 2) return 100000;
        int score = 0;
        int whitePieces = 0, blackPieces = 0, whiteKings = 0, blackKings = 0;
        int whiteMobility = getAllPossibleMovesForPlayer(currentBoard, true).size();
        int blackMobility = getAllPossibleMovesForPlayer(currentBoard, false).size();
        score += (blackMobility - whiteMobility) * 2;
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                int piece = currentBoard[r][c];
                if (piece != 0) {
                    if (isWhitePiece(piece)) {
                        whitePieces++;
                        if (isKing(piece)) whiteKings++;
                        else score -= (7 - r);
                    } else {
                        blackPieces++;
                        if (isKing(piece)) blackKings++;
                        else {
                            score += r;
                            if (c == 0 || c == 7) score += 5;
                        }
                    }
                }
            }
        }
        score += (blackPieces - whitePieces) * 100;
        score += (blackKings - whiteKings) * (KING_VALUE * 100);
        return score;
    }

    private int checkGameStatus(int[][] currentBoard) {
        boolean whiteHasMoves = !getAllPossibleMovesForPlayer(currentBoard, true).isEmpty();
        boolean blackHasMoves = !getAllPossibleMovesForPlayer(currentBoard, false).isEmpty();
        if (!whiteHasMoves) return 2;
        if (!blackHasMoves) return 1;
        return 0;
    }

    private List<List<int[]>> getAllPossibleMovesForPlayer(int[][] currentBoard, boolean isWhitePlayer) {
        List<List<int[]>> captureMoves = getAllCaptureMovesForPlayer(currentBoard, isWhitePlayer);
        if (!captureMoves.isEmpty()) {
            return captureMoves;
        }
        return getSimpleMovesForPlayer(currentBoard, isWhitePlayer);
    }

    private List<List<int[]>> getSimpleMovesForPlayer(int[][] currentBoard, boolean isWhitePlayer) {
        List<List<int[]>> allMoves = new ArrayList<>();
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                if (isPlayerPiece(currentBoard[r][c], isWhitePlayer)) {
                    List<int[]> moves = getSimpleMovesForPiece(currentBoard, r, c);
                    for (int[] move : moves) {
                        List<int[]> sequence = new ArrayList<>();
                        sequence.add(new int[]{r, c});
                        sequence.add(move);
                        allMoves.add(sequence);
                    }
                }
            }
        }
        return allMoves;
    }

    private List<int[]> getSimpleMovesForPiece(int[][] currentBoard, int r, int c) {
        List<int[]> moves = new ArrayList<>();
        int piece = currentBoard[r][c];
        if (isKing(piece)) {
            int[] dr = {-1, -1, 1, 1};
            int[] dc = {-1, 1, -1, 1};
            for (int i = 0; i < 4; i++) {
                int tempR = r + dr[i];
                int tempC = c + dc[i];
                while (inBounds(tempR, tempC) && currentBoard[tempR][tempC] == 0) {
                    moves.add(new int[]{tempR, tempC});
                    tempR += dr[i];
                    tempC += dc[i];
                }
            }
        } else {
            int forwardDir = isWhitePiece(piece) ? -1 : 1;
            for (int dc : new int[]{-1, 1}) {
                int newR = r + forwardDir;
                int newC = c + dc;
                if (inBounds(newR, newC) && currentBoard[newR][newC] == 0) {
                    moves.add(new int[]{newR, newC});
                }
            }
        }
        return moves;
    }

    private List<List<int[]>> getAllCaptureMovesForPlayer(int[][] currentBoard, boolean isWhitePlayer) {
        List<List<int[]>> allSequences = new ArrayList<>();
        int maxCaptureLength = 0;
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                if (isPlayerPiece(currentBoard[r][c], isWhitePlayer)) {
                    List<List<int[]>> sequencesForPiece = findCaptureSequences(currentBoard, r, c);
                    if (!sequencesForPiece.isEmpty()) {
                        int currentMaxLength = countCapturesInSequence(sequencesForPiece.get(0));
                        if (currentMaxLength > maxCaptureLength) {
                            maxCaptureLength = currentMaxLength;
                            allSequences.clear();
                        }
                        if (currentMaxLength == maxCaptureLength && currentMaxLength > 0) {
                            allSequences.addAll(sequencesForPiece);
                        }
                    }
                }
            }
        }
        return allSequences;
    }

    private int countCapturesInSequence(List<int[]> sequence) {
        if (sequence == null || sequence.size() < 2) return 0;
        int captures = 0;
        for (int i = 0; i < sequence.size() - 1; i++) {
            if (Math.abs(sequence.get(i)[0] - sequence.get(i + 1)[0]) >= 2) {
                captures++;
            }
        }
        return captures;
    }

    private List<List<int[]>> findCaptureSequences(int[][] currentBoard, int r, int c) {
        List<List<int[]>> allPaths = new ArrayList<>();
        List<int[]> initialPath = new ArrayList<>();
        initialPath.add(new int[]{r, c});
        findCapturePathsRecursive(currentBoard, r, c, initialPath, allPaths, new boolean[BOARD_SIZE][BOARD_SIZE]);
        if (allPaths.isEmpty() || countCapturesInSequence(allPaths.get(0)) == 0) return new ArrayList<>();
        List<List<int[]>> longestPaths = new ArrayList<>();
        int maxLen = 0;
        for (List<int[]> path : allPaths) {
            int captures = countCapturesInSequence(path);
            if (captures > maxLen) {
                maxLen = captures;
                longestPaths.clear();
            }
            if (captures == maxLen && maxLen > 0) {
                longestPaths.add(path);
            }
        }
        return longestPaths;
    }

    private void findCapturePathsRecursive(int[][] currentBoard, int r, int c, List<int[]> currentPath, List<List<int[]>> allPaths, boolean[][] visited) {
        int piece = currentBoard[r][c];
        List<int[]> possibleJumps = new ArrayList<>();
        int[] dr = {-1, -1, 1, 1};
        int[] dc = {-1, 1, -1, 1};
        if (isKing(piece)) {
            for (int i = 0; i < 4; i++) {
                int tempR = r + dr[i];
                int tempC = c + dc[i];
                int opponentR = -1;
                while (inBounds(tempR, tempC)) {
                    if (isOpponent(piece, currentBoard[tempR][tempC]) && !visited[tempR][tempC]) {
                        if (opponentR == -1) {
                            opponentR = tempR;
                        } else break;
                    } else if (currentBoard[tempR][tempC] != 0) break;
                    if (opponentR != -1 && currentBoard[tempR][tempC] == 0) {
                        possibleJumps.add(new int[]{tempR, tempC, opponentR, (r + tempR) / 2});
                    }
                    tempR += dr[i];
                    tempC += dc[i];
                }
            }
        } else {
            for (int i = 0; i < 4; i++) {
                int opponentR = r + dr[i];
                int opponentC = c + dc[i];
                int destR = r + 2 * dr[i];
                int destC = c + 2 * dc[i];
                if (inBounds(destR, destC) && currentBoard[destR][destC] == 0 && isOpponent(piece, currentBoard[opponentR][opponentC])) {
                    possibleJumps.add(new int[]{destR, destC, opponentR, opponentC});
                }
            }
        }
        boolean foundNextJump = false;
        for (int[] jump : possibleJumps) {
            foundNextJump = true;
            int destR = jump[0], destC = jump[1], capturedR = jump[2], capturedC = jump[3];
            int[][] nextBoard = copyBoard(currentBoard);
            nextBoard[destR][destC] = piece;
            nextBoard[r][c] = 0;
            nextBoard[capturedR][capturedC] = 0;
            List<int[]> nextPath = new ArrayList<>(currentPath);
            nextPath.add(new int[]{destR, destC});
            boolean promotionOccurred = !isKing(piece) && ((isWhitePiece(piece) && destR == 0) || (isBlackPiece(piece) && destR == BOARD_SIZE - 1));
            if (promotionOccurred) {
                allPaths.add(nextPath);
            } else {
                boolean[][] nextVisited = new boolean[BOARD_SIZE][BOARD_SIZE];
                for (int i = 0; i < BOARD_SIZE; i++) System.arraycopy(visited[i], 0, nextVisited[i], 0, BOARD_SIZE);
                nextVisited[capturedR][capturedC] = true;
                findCapturePathsRecursive(nextBoard, destR, destC, nextPath, allPaths, nextVisited);
            }
        }
        if (!foundNextJump && countCapturesInSequence(currentPath) > 0) {
            allPaths.add(new ArrayList<>(currentPath));
        }
    }

    private long getBoardHash(int[][] currentBoard) {
        long hash = 0;
        for (int r = 0; r < BOARD_SIZE; r++) {
            for (int c = 0; c < BOARD_SIZE; c++) {
                int piece = currentBoard[r][c];
                if (piece != 0) {
                    hash ^= zobristTable[r][c][piece];
                }
            }
        }
        return hash;
    }
    private boolean inBounds(int r, int c) { return r >= 0 && r < BOARD_SIZE && c >= 0 && c < BOARD_SIZE; }
    private boolean isWhitePiece(int piece) { return piece == 1 || piece == 3; }
    private boolean isBlackPiece(int piece) { return piece == 2 || piece == 4; }
    private boolean isKing(int piece) { return piece == 3 || piece == 4; }
    private boolean isPlayerPiece(int piece, boolean isWhite) { return isWhite ? isWhitePiece(piece) : isBlackPiece(piece); }
    private boolean isOpponent(int piece, int otherPiece) { if (otherPiece == 0) return false; return (isWhitePiece(piece) && isBlackPiece(otherPiece)) || (isBlackPiece(piece) && isWhitePiece(otherPiece)); }
    private int[][] copyBoard(int[][] original) {
        int[][] copy = new int[BOARD_SIZE][BOARD_SIZE];
        for (int i = 0; i < BOARD_SIZE; i++) {
            System.arraycopy(original[i], 0, copy[i], 0, BOARD_SIZE);
        }
        return copy;
    }
            }
