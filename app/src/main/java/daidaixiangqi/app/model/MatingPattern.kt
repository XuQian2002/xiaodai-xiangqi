package daidaixiangqi.app.model

/**
 * 保守、规则化的常见杀法名识别。
 *
 * 仅在局面与最后一步同时满足高置信条件时返回杀法名；任何拿不准的局面一律返回 null，
 * 由播报层退回基础句（记谱 + 将死），不臆造杀法名。识别准确率需在真实对局装机验证。
 *
 * 已规则化（18 种）：对面笑、双将、重炮、马后炮、双车错、挂角马、卧槽马、钓鱼马、
 * 大胆穿心、闷宫、天地炮、花心兵、夹车炮、二鬼拍门、铁门栓、中肋炮、空头炮、三子归边。
 * （困毙由终局判决分支单独播报，不在本杀招名表内。）
 *
 * 全网 36 招体系中暂不自动命名的部分，按原因分组如下。App 只看得到终局一步的静态局面，
 * 这类杀招要么依赖连续多步着法、要么属于残局定式、要么与已识别杀招阵型重叠需着法历史
 * 才能区分；强行按静态位置命名误报风险高，故判不准时退回基础句（记谱 + 将死）。
 *
 * 一、需多步连将/轮番抽将，静态一步无法还原过程：
 * 双马饮泉、拔簧马、车马冷着、车炮抽杀（车炮滚杀）、炮碾丹砂（打剥皮）、进洞出洞、
 * 白马现蹄（立马车）、列马车、送佛归殿（三进兵/太监追皇上/凤凰三点头/金鸡三点头）。
 *
 * 二、残局定式，依赖特定子力对比与残局演进：
 * 海底捞月（沉底月）、老卒搜山（老兵搜山/老卒搜林）、三车闹士。
 *
 * 三、与已识别杀招阵型重叠或点位相邻，静态难以稳妥区分：
 * 八角马（挂角马的特殊形，已并入挂角马播报）、侧面虎（高钓马，邻钓鱼马点位）、
 * 平顶冠（铁门栓 + 空头炮的组合形）、双车胁士（近大胆穿心/双车错）、闷杀（闷将，近闷宫）、
 * 臣压君、小鬼扼喉（近二鬼拍门）、泰山压顶、命门堵杀、双杯献酒、借炮使马、掖车挂、
 * 花心采蜜、金钩马。
 *
 * 别名归并：对面笑=白脸将=千里照面；大胆穿心=大刀剜心=小刀剜心=穿心杀；钓鱼马=列马车；
 * 二鬼拍门=双鬼拍门；花心兵=小鬼坐龙庭；夹车炮=二路夹车炮。
 */
object MatingPattern {

    fun detect(board: Board, lastMove: Move?, attacker: PieceColor): String? {
        if (lastMove == null) return null
        if (lastMove.piece.color != attacker) return null
        val defender = attacker.opposite()
        val general = board.getPiecesByColor(defender).find { it.type == PieceType.GENERAL }
            ?: return null
        val generalPos = general.position

        // 注意：Move.piece 记录的是走动前的原位棋子，终局盘上棋子已落在 lastMove.to。
        // 因此走动棋子一律从终局盘落点取回，保证位置、合法着法都基于终局局面。
        val movedPiece = board.getPiece(lastMove.to)?.takeIf { it.color == attacker }
            ?: lastMove.piece

        // 对面笑（白脸将）：将帅同一直线且中间无子，直接对面成杀。
        if (board.isGeneralsFacing()) return "对面笑"

        // 双将：攻方两枚不同兵种棋子的合法着法同时直指对方将所在格。
        val checkerTypes = board.getPiecesByColor(attacker).filter { piece ->
            checks(board, piece, generalPos)
        }.map { it.type }.toSet()
        if (checkerTypes.size >= 2) return "双将"

        return when (movedPiece.type) {
            PieceType.CANNON ->
                detectDoubleCannon(board, attacker, generalPos, movedPiece)
                    ?: detectHorseBehindCannon(board, attacker, generalPos, movedPiece)
                    ?: detectSkyEarthCannon(board, attacker, defender, generalPos, movedPiece)
                    ?: detectMutedPalace(board, defender, generalPos, movedPiece)
                    ?: detectRibCannon(board, attacker, generalPos)
                    ?: detectEmptyHeadCannon(board, generalPos, movedPiece)
                    ?: detectSandwichChariot(board, attacker, generalPos)
                    ?: detectThreeWing(board, attacker, generalPos)
            PieceType.HORSE ->
                detectHorseMating(board, defender, generalPos, movedPiece)
                    ?: detectThreeWing(board, attacker, generalPos)
            PieceType.CHARIOT ->
                detectTwinChariots(board, attacker, generalPos, movedPiece)
                    ?: detectHeartChariot(board, defender, generalPos, movedPiece, lastMove.capturedPiece)
                    ?: detectIronBolt(board, attacker, generalPos)
                    ?: detectSandwichChariot(board, attacker, generalPos)
                    ?: detectThreeWing(board, attacker, generalPos)
            PieceType.SOLDIER ->
                detectTwinSoldiers(board, attacker, defender)
                    ?: detectSoldierMating(board, defender, generalPos, movedPiece)
                    ?: detectIronBolt(board, attacker, generalPos)
            else -> null
        }
    }

    private fun checks(board: Board, piece: Piece, general: Position): Boolean =
        piece.getLegalMoves(board).any { it.to == general }

    private fun aligned(a: Position, b: Position): Boolean =
        a.row == b.row || a.col == b.col

    private fun between(board: Board, from: Position, to: Position): List<Piece> {
        val out = mutableListOf<Piece>()
        if (from.row == to.row) {
            val (lo, hi) = minOf(from.col, to.col) to maxOf(from.col, to.col)
            for (col in (lo + 1) until hi) {
                board.getPiece(Position(from.row, col))?.let { out.add(it) }
            }
        } else if (from.col == to.col) {
            val (lo, hi) = minOf(from.row, to.row) to maxOf(from.row, to.row)
            for (row in (lo + 1) until hi) {
                board.getPiece(Position(row, from.col))?.let { out.add(it) }
            }
        }
        return out
    }

    private fun inPalace(pos: Position, defender: PieceColor): Boolean {
        val rows = if (defender == PieceColor.BLACK) 0..2 else 7..9
        return pos.row in rows && pos.col in 3..5
    }

    /** 挂角马士角。 */
    private fun hangingCorners(defender: PieceColor): List<Position> =
        if (defender == PieceColor.BLACK) {
            listOf(Position(0, 3), Position(0, 5), Position(2, 3), Position(2, 5))
        } else {
            listOf(Position(9, 3), Position(9, 5), Position(7, 3), Position(7, 5))
        }

    /** 卧槽马点位。 */
    private fun neckHorseSpots(defender: PieceColor): List<Position> =
        if (defender == PieceColor.BLACK) {
            listOf(Position(1, 1), Position(1, 7))
        } else {
            listOf(Position(8, 1), Position(8, 7))
        }

    /** 钓鱼马点位。 */
    private fun fishingHorseSpots(defender: PieceColor): List<Position> =
        if (defender == PieceColor.BLACK) {
            listOf(Position(2, 2), Position(2, 6))
        } else {
            listOf(Position(7, 2), Position(7, 6))
        }

    /** 重炮：两枚攻方炮与对方将共线，前炮挡将、后炮以前炮为炮架将军。 */
    private fun detectDoubleCannon(
        board: Board,
        attacker: PieceColor,
        general: Position,
        moved: Piece
    ): String? {
        val cannons = board.getPiecesByColor(attacker).filter { it.type == PieceType.CANNON }
        if (cannons.size < 2) return null

        for (rear in cannons) {
            for (front in cannons) {
                if (rear.position == front.position) continue
                if (!aligned(rear.position, front.position) || !aligned(front.position, general)) continue
                val rearScreen = between(board, rear.position, general)
                val frontScreen = between(board, front.position, general)
                if (rearScreen.size == 1 && rearScreen[0].position == front.position && frontScreen.isEmpty()) {
                    if (moved.position == rear.position || moved.position == front.position) {
                        return "重炮"
                    }
                }
            }
        }
        return null
    }

    /** 马后炮：最后一步炮与将共线，炮架是己方马。 */
    private fun detectHorseBehindCannon(
        board: Board,
        attacker: PieceColor,
        general: Position,
        moved: Piece
    ): String? {
        val cannon = moved.position
        if (!aligned(cannon, general)) return null
        val screen = between(board, cannon, general)
        if (screen.size != 1) return null
        val horse = board.getPiece(screen[0].position) ?: return null
        return if (horse.type == PieceType.HORSE && horse.color == attacker) "马后炮" else null
    }

    /** 天地炮：一炮镇中路、一炮沉对方底线，上下夹击。 */
    private fun detectSkyEarthCannon(
        board: Board,
        attacker: PieceColor,
        defender: PieceColor,
        general: Position,
        moved: Piece
    ): String? {
        val cannons = board.getPiecesByColor(attacker).filter { it.type == PieceType.CANNON }
        if (cannons.size < 2) return null
        val bottomRow = if (defender == PieceColor.BLACK) 0 else 9
        val middle = cannons.any { it.position.col == general.col && aligned(it.position, general) }
        val bottom = cannons.any { it.position.row == bottomRow }
        return if (middle && bottom && moved.type == PieceType.CANNON) "天地炮" else null
    }

    /** 闷宫：炮借对方士作炮架将军，将的逃路被己方棋子堵死。 */
    private fun detectMutedPalace(
        board: Board,
        defender: PieceColor,
        general: Position,
        moved: Piece
    ): String? {
        val cannon = moved.position
        if (!aligned(cannon, general)) return null
        val screen = between(board, cannon, general)
        if (screen.size != 1) return null
        val screenPiece = screen[0]
        if (screenPiece.color != defender || screenPiece.type != PieceType.ADVISOR) return null

        val neighbors = listOf(
            Position(general.row - 1, general.col),
            Position(general.row + 1, general.col),
            Position(general.row, general.col - 1),
            Position(general.row, general.col + 1)
        )
        var blocked = 0
        for (n in neighbors) {
            if (!n.isValid() || !inPalace(n, defender)) { blocked++; continue }
            val occupant = board.getPiece(n)
            if (occupant != null && occupant.color == defender) blocked++
        }
        return if (blocked >= 2) "闷宫" else null
    }

    /** 双车错：最后一步车直接将军，另一攻方车亦与将共线且中间无子遮挡。 */
    private fun detectTwinChariots(
        board: Board,
        attacker: PieceColor,
        general: Position,
        moved: Piece
    ): String? {
        val chariots = board.getPiecesByColor(attacker).filter { it.type == PieceType.CHARIOT }
        if (chariots.size < 2) return null

        val movedPos = moved.position
        if (!aligned(movedPos, general) || between(board, movedPos, general).isNotEmpty()) return null

        val other = chariots.find { it.position != movedPos } ?: return null
        return if (aligned(other.position, general) && between(board, other.position, general).isEmpty()) {
            "双车错"
        } else {
            null
        }
    }

    /** 大胆穿心（大刀剜心）：车强吃对方九宫士并将军。 */
    private fun detectHeartChariot(
        board: Board,
        defender: PieceColor,
        general: Position,
        moved: Piece,
        captured: Piece?
    ): String? {
        if (captured?.type != PieceType.ADVISOR) return null
        if (!inPalace(moved.position, defender)) return null
        return if (checks(board, moved, general)) "大胆穿心" else null
    }

    /** 马类杀招：挂角马 > 卧槽马 > 钓鱼马，按点位与将军条件匹配。 */
    private fun detectHorseMating(
        board: Board,
        defender: PieceColor,
        general: Position,
        moved: Piece
    ): String? {
        if (!checks(board, moved, general)) return null
        return when {
            moved.position in hangingCorners(defender) -> "挂角马"
            moved.position in neckHorseSpots(defender) -> "卧槽马"
            moved.position in fishingHorseSpots(defender) -> "钓鱼马"
            else -> null
        }
    }

    /** 花心兵：兵冲入对方九宫中心并将军。 */
    private fun detectSoldierMating(
        board: Board,
        defender: PieceColor,
        general: Position,
        moved: Piece
    ): String? {
        val heart = if (defender == PieceColor.BLACK) Position(1, 4) else Position(8, 4)
        if (moved.position != heart) return null
        return if (checks(board, moved, general)) "花心兵" else null
    }

    /** 夹车炮：一车双炮集于对方将的同一侧翼配合作杀。 */
    private fun detectSandwichChariot(
        board: Board,
        attacker: PieceColor,
        general: Position
    ): String? {
        val chariots = board.getPiecesByColor(attacker).filter { it.type == PieceType.CHARIOT }
        val cannons = board.getPiecesByColor(attacker).filter { it.type == PieceType.CANNON }
        if (chariots.size < 1 || cannons.size < 2) return null
        val wingPieces = chariots + cannons
        val left = wingPieces.all { it.position.col < general.col }
        val right = wingPieces.all { it.position.col > general.col }
        return if (left || right) "夹车炮" else null
    }

    /** 二鬼拍门：双兵分别锁住对方九宫两条肋道。 */
    private fun detectTwinSoldiers(
        board: Board,
        attacker: PieceColor,
        defender: PieceColor
    ): String? {
        val soldiers = board.getPiecesByColor(attacker)
            .filter { it.type == PieceType.SOLDIER }
        val palaceRows = if (defender == PieceColor.BLACK) 0..2 else 7..9
        val left = soldiers.any { it.position.row in palaceRows && it.position.col == 3 }
        val right = soldiers.any { it.position.row in palaceRows && it.position.col == 5 }
        return if (left && right) "二鬼拍门" else null
    }

    /** 铁门栓：一炮镇中路，车或兵封住肋道。 */
    private fun detectIronBolt(
        board: Board,
        attacker: PieceColor,
        general: Position
    ): String? {
        val mine = board.getPiecesByColor(attacker)
        val midCannon = mine.any {
            it.type == PieceType.CANNON && it.position.col == general.col &&
                aligned(it.position, general)
        }
        if (!midCannon) return null
        val rib = mine.any {
            (it.type == PieceType.CHARIOT || it.type == PieceType.SOLDIER) &&
                (it.position.col == 3 || it.position.col == 5)
        }
        return if (rib) "铁门栓" else null
    }

    /** 中肋炮：一炮镇中路，另一炮镇肋道。 */
    private fun detectRibCannon(
        board: Board,
        attacker: PieceColor,
        general: Position
    ): String? {
        val cannons = board.getPiecesByColor(attacker).filter { it.type == PieceType.CANNON }
        if (cannons.size < 2) return null
        val mid = cannons.any { it.position.col == general.col && aligned(it.position, general) }
        val rib = cannons.any { it.position.col == 3 || it.position.col == 5 }
        return if (mid && rib) "中肋炮" else null
    }

    /** 空头炮：炮与将同线且中间无子，直面压制。 */
    private fun detectEmptyHeadCannon(
        board: Board,
        general: Position,
        moved: Piece
    ): String? {
        if (moved.type != PieceType.CANNON) return null
        if (!aligned(moved.position, general)) return null
        return if (between(board, moved.position, general).isEmpty()) "空头炮" else null
    }

    /** 三子归边：车、马、炮三类大子集中在对方同一侧翼。 */
    private fun detectThreeWing(
        board: Board,
        attacker: PieceColor,
        general: Position
    ): String? {
        val majors = board.getPiecesByColor(attacker).filter {
            it.type == PieceType.CHARIOT || it.type == PieceType.HORSE || it.type == PieceType.CANNON
        }
        if (majors.size < 3) return null
        val left = majors.all { it.position.col < general.col }
        val right = majors.all { it.position.col > general.col }
        return if (left || right) "三子归边" else null
    }
}
