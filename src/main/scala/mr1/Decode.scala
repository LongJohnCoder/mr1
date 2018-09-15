
package mr1

import spinal.core._

case class Decode2Execute(config: MR1Config) extends Bundle {

    val valid           = Bool
    val pc              = UInt(config.pcSize bits)
    val instr           = Bits(32 bits)
    val itype           = InstrType()
    val op1_33          = Bits(33 bits)
    val op2_33          = Bits(33 bits)
    val op1_op2_lsb     = Bits(9 bits)
    val rs2_imm         = Bits(32 bits)
    val rd_valid        = Bool

    val rvfi = if (config.hasFormal) RVFI(config) else null

    def init() : Decode2Execute = {
        valid init(False)
        if (config.hasFormal) rvfi init()
        this
    }

}

case class Execute2Decode(config: MR1Config) extends Bundle {

    val stall   = Bool

    val pc_jump_valid = Bool                // FIXME: This is probably redundant with stall, but let's leave it for now.
    val pc_jump       = UInt(config.pcSize bits)

    val rd_addr_valid = Bool
    val rd_addr       = UInt(5 bits)
}

class Decode(config: MR1Config) extends Component {

    val hasMul   = config.hasMul
    val hasDiv   = config.hasDiv
    val hasCsr   = config.hasCsr
    val hasFence = config.hasFence

    val io = new Bundle {
        val f2d         = in(Fetch2Decode(config))
        val d2f         = out(Decode2Fetch(config))

        val r2rr        = in(RegFile2ReadResult(config))

        val d2e         = out(Reg(Decode2Execute(config)) init).addAttribute("keep")
        val e2d         = in(Execute2Decode(config))
    }

    val instr       = io.f2d.instr

    val d2f_stall_d = RegNext(io.d2f.stall, False)
    val f2d_valid_d = RegNext(io.f2d.valid, False)
    val decode_start    = io.f2d.valid && !d2f_stall_d
    val decode_end      = io.f2d.valid && !io.d2f.stall
    val decode_go_idle  = !io.f2d.valid && f2d_valid_d

    object Op1Kind extends SpinalEnum {
        val Rs1     = newElement()
        val Zero    = newElement()
        val Pc      = newElement()
    }

    val decode = new Area {

        val opcode      = instr(6 downto 0)
        val funct3      = instr(14 downto 12)
        val funct7      = instr(31 downto 25)
        val rd_addr     = U(instr(11 downto 7))
        val rs1_addr    = U(instr(19 downto 15))
        val rs2_addr    = U(instr(24 downto 20))

        val itype       = InstrType()
        val iformat     = InstrFormat()
        val sub           = False
        val unsigned      = False

        iformat         := InstrFormat.Undef
        itype           := InstrType.Undef

        val op1_kind = Op1Kind()
        op1_kind := Op1Kind.Rs1

        switch(opcode){
            is(Opcodes.LUI){
                itype               := InstrType.ALU_ADD
                iformat             := InstrFormat.U
                op1_kind            := Op1Kind.Zero
            }
            is(Opcodes.AUIPC){
                itype               := InstrType.ALU_ADD
                iformat             := InstrFormat.U
                op1_kind            := Op1Kind.Pc
            }
            is(Opcodes.JAL){
                itype               := InstrType.JAL
                iformat             := InstrFormat.J
                op1_kind            := Op1Kind.Pc
            }
            is(Opcodes.JALR){
                when(funct3 === B"000") {
                    itype           := InstrType.JALR
                    iformat         := InstrFormat.I
                }
            }
            is(Opcodes.B){
                when(funct3 =/= B"010" && funct3 =/= B"011") {
                    itype           := InstrType.B
                    iformat         := InstrFormat.B
                    unsigned        := (funct3(2 downto 1) === B"11")
                    sub             := (funct3(2 downto 1) =/= B"00")
                }
            }
            is(Opcodes.L){
                when(funct3 =/= B"011" && funct3 =/= B"110" && funct3 =/= B"111") {
                    itype           := InstrType.L
                    iformat         := InstrFormat.I
                }
            }
            is(Opcodes.S){
                when(funct3 === B"000" || funct3 === B"001" || funct3 === B"010") {
                    itype           := InstrType.S
                    iformat         := InstrFormat.S
                }
            }
            is(Opcodes.ALUI){
                when(funct3 === B"000"){
                    itype           := InstrType.ALU_ADD
                    iformat         := InstrFormat.I
                }
                .elsewhen(funct3 === B"010" || funct3 === B"011") {
                    // ALU_I: SLTI, SLTIU
                    itype           := InstrType.ALU
                    iformat         := InstrFormat.I
                    unsigned        := funct3(0)
                    sub             := True
                }
                .elsewhen(funct3 === B"100" || funct3 === B"110" || funct3 === B"111") {
                    // ALU_I: XORI, ORI, ANDI
                    itype           := InstrType.ALU
                    iformat         := InstrFormat.I
                }
                .elsewhen( (funct7 ## funct3) === B"0000000001" || (funct7 ## funct3) === B"0000000101" || (funct7 ## funct3) === B"0100000101") {
                    // SHIFT_I: SLLI, SRLI, SRAI
                    itype           := InstrType.SHIFT
                    iformat         := InstrFormat.Shamt
                }
            }
            is(Opcodes.ALU){
                switch(funct7 ## funct3){
                    is(B"0000000_000", B"0100000_000"){
                        // ADD, SUB
                        itype           := InstrType.ALU_ADD
                        iformat         := InstrFormat.R
                        sub             := funct7(5)
                    }
                    is(B"0000000_100", B"0000000_110", B"0000000_111"){
                        // ADD, SUB, XOR, OR, AND
                        itype           := InstrType.ALU
                        iformat         := InstrFormat.R
                    }
                    is(B"0000000_001", B"0000000_101", B"0100000_101"){
                        // SLL, SRL, SRA
                        itype           := InstrType.SHIFT
                        iformat         := InstrFormat.R
                    }
                    is( B"0000000_010", B"0000000_011") {
                        // SLT, SLTU
                        itype           := InstrType.ALU
                        iformat         := InstrFormat.R
                        unsigned        := funct3(0)
                        sub             := True
                    }
                    is(B"0000001_000", B"0000001_001", B"0000001_010", B"0000001_011"){
                        // MUL
                        if (hasMul){
                            when(funct7 === B"0000001"){
                                itype       := InstrType.MULDIV
                                iformat     := InstrFormat.R
                            }
                        }
                    }
                    is(B"0000001_100", B"0000001_101", B"0000001_110", B"0000001_111"){
                        // DIV
                        if (hasDiv){
                            when(funct7 === B"0000001"){
                                itype       := InstrType.MULDIV
                                iformat     := InstrFormat.R
                            }
                        }
                    }
                }
            }
            is(Opcodes.F){
                if (hasFence){
                    when( funct3 === B"000" || funct3 === B"001"){
                        itype       := InstrType.FENCE
                        iformat     := InstrFormat.I
                    }
                }
            }
            // ECALL, EBREAK, CSR
            is(Opcodes.SYS){
                when( instr(31 downto 7) === B"0000_0000_0000_0000_0000_0000_0" || instr(31 downto 7) === B"0000_0000_0001_0000_0000_0000_0")
                {
                    itype       := InstrType.E
                    iformat     := InstrFormat.I
                }.elsewhen(funct3 === B"001" || funct3 === B"010" || funct3 === B"011" || funct3 === B"101" || funct3 === B"110" || funct3 === B"111") {
                    if (hasCsr){
                        itype       := InstrType.CSR
                        iformat     := InstrFormat.I
                    }
                }
            }
        }

    }

    val i_imm = S(B((19 downto 0) -> instr(31)) ## instr(31 downto 20))
    val s_imm = S(B((19 downto 0) -> instr(31)) ## instr(31 downto 25) ## instr(11 downto 7))
    val b_imm = S(B((19 downto 0) -> instr(31)) ## instr(7) ## instr(30 downto 25) ## instr(11 downto 8) ## "0")
    val j_imm = S(B((10 downto 0) -> instr(31)) ## instr(31) ## instr(19 downto 12) ## instr(20) ## instr(30 downto 21) ## "0")
    val u_imm = S(instr(31 downto 12) ## B((11 downto 0) -> false))

    io.d2f.pc_jump_valid := io.e2d.pc_jump_valid
    io.d2f.pc_jump       := io.e2d.pc_jump

    val rs1_valid =  (decode.iformat === InstrFormat.R) ||
                     (decode.iformat === InstrFormat.I) ||
                     (decode.iformat === InstrFormat.S) ||
                     (decode.iformat === InstrFormat.B) ||
                     (decode.iformat === InstrFormat.Shamt)

    val rs2_valid =  (decode.iformat === InstrFormat.R) ||
                     (decode.iformat === InstrFormat.S) ||
                     (decode.iformat === InstrFormat.B)

    val rd_valid =   (decode.iformat === InstrFormat.R) ||
                     (decode.iformat === InstrFormat.I) ||
                     (decode.iformat === InstrFormat.U) ||
                     (decode.iformat === InstrFormat.J) ||
                     (decode.iformat === InstrFormat.Shamt)

    val rs1_33 = decode.unsigned ? B(U(io.r2rr.rs1_data).resize(33)) | B(S(io.r2rr.rs1_data).resize(33))
    val rs2_33 = decode.unsigned ? B(U(io.r2rr.rs2_data).resize(33)) | B(S(io.r2rr.rs2_data).resize(33))

    val op1_33 = Bits(33 bits)
    op1_33 := decode.op1_kind.mux(
        Op1Kind.Rs1     -> rs1_33,
        Op1Kind.Zero    -> B"33'd0",
        Op1Kind.Pc      -> B(io.f2d.pc).resize(33)
    )

    val sub      = decode.sub
    val unsigned = decode.unsigned

    val op2_33 = Bits(33 bits)
    op2_33 := decode.iformat.mux(
            InstrFormat.R       -> rs2_33,
            InstrFormat.I       -> B(unsigned ? U(i_imm).resize(33) | U(i_imm.resize(33)) ),
            InstrFormat.S       -> B(s_imm.resize(33)),
            InstrFormat.U       -> B(u_imm.resize(33)),
            InstrFormat.Shamt   -> rs2_33(32 downto 5) ## instr(24 downto 20),
            default             -> rs2_33
            ) ^ B(33 bits, default -> sub)

    val op1_op2_lsb = B((U(False ## op1_33(7 downto 0) ## sub) + U(False ## op2_33(7 downto 0) ## sub)))(9 downto 1)

    val rs2_imm = Bits(32 bits)
    rs2_imm := decode.iformat.mux(
            InstrFormat.I       -> io.r2rr.rs2_data(31 downto 21) ## i_imm(20 downto 0),
            InstrFormat.B       -> io.r2rr.rs2_data(31 downto 21) ## b_imm(20 downto 0),
            InstrFormat.J       -> io.r2rr.rs2_data(31 downto 21) ## j_imm(20 downto 0),
            default             -> io.r2rr.rs2_data
            )

    io.d2f.stall         := io.e2d.stall
    io.d2f.rd_addr_valid := decode_end && rd_valid
    io.d2f.rd_addr       := decode.rd_addr

    val formal = if (config.hasFormal) new Area {

        val rvfi = RVFI(config)

        val order = Reg(UInt(64 bits)) init(0)
        when(decode_end){
            order := order + 1
        }

        rvfi.valid      := decode_end
        rvfi.order      := order
        rvfi.insn       := io.f2d.instr
        rvfi.trap       := (decode.iformat === InstrFormat.Undef)
        rvfi.halt       := False
        rvfi.intr       := False
        rvfi.rs1_addr   := rs1_valid ? decode.rs1_addr | 0
        rvfi.rs2_addr   := rs2_valid ? decode.rs2_addr | 0
        rvfi.rs1_rdata  := rs1_valid ? io.r2rr.rs1_data | 0
        rvfi.rs2_rdata  := rs2_valid ? io.r2rr.rs2_data | 0
        rvfi.rd_addr    := rd_valid ?  decode.rd_addr | 0
        rvfi.rd_wdata   := 0
        rvfi.pc_rdata   := io.f2d.pc.resize(32)
        rvfi.pc_wdata   := 0
        rvfi.mem_addr   := 0
        rvfi.mem_rmask  := 0
        rvfi.mem_wmask  := 0
        rvfi.mem_rdata  := 0
        rvfi.mem_wdata  := 0
    } else null

    val d2e = new Area {
        val d2e_nxt     = Decode2Execute(config).setName("d2e_nxt")

        d2e_nxt.valid           := io.f2d.valid 
        d2e_nxt.pc              := io.f2d.pc
        d2e_nxt.itype           := decode.itype
        d2e_nxt.instr           := instr
        d2e_nxt.op1_33          := op1_33
        d2e_nxt.op2_33          := op2_33
        d2e_nxt.op1_op2_lsb     := op1_op2_lsb
        d2e_nxt.rs2_imm         := rs2_imm
        d2e_nxt.rd_valid        := rd_valid

        if (config.hasFormal)
            d2e_nxt.rvfi            := formal.rvfi

        when(io.f2d.valid && !io.e2d.stall){
            io.d2e          := d2e_nxt
        }
        .elsewhen(!io.e2d.stall && io.d2e.valid){
            io.d2e.valid    := False
        }

    }
}


