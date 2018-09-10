
package mr1

import spinal.core._


class Execute(config: MR1Config) extends Component {

    val hasMul   = config.hasMul
    val hasDiv   = config.hasDiv
    val hasCsr   = config.hasCsr
    val hasFence = config.hasFence

    val io = new Bundle {
        val d2e         = in(Decode2Execute(config))
        val e2d         = out(Execute2Decode(config))
        val e2f         = out(Execute2Fetch(config))

        val w2r         = out(Write2RegFile(config))

        val data_req    = DataReqIntfc(config)
        val data_rsp    = DataRspIntfc(config)

        val rvfi        = if (config.hasFormal) out(Reg(RVFI(config)) init) else null
    }

    val e2d_stall_d = RegNext(io.e2d.stall, False)

    val exe_start = io.d2e.valid && !e2d_stall_d
    val exe_end   = io.d2e.valid && !io.e2d.stall

    val iformat     = InstrFormat()
    val itype       = InstrType()
    val sub         = Bool
    val instr       = Bits(32 bits)
    val funct3      = Bits(3 bits)
    val rd_addr     = UInt(5 bits)
    val rd_addr_valid   = Bool

    iformat     := io.d2e.decoded_instr.iformat
    itype       := io.d2e.decoded_instr.itype
    sub         := io.d2e.decoded_instr.sub
    instr       := io.d2e.instr
    funct3      := instr(14 downto 12)
    rd_addr     := U(instr(11 downto 7))

    val rd_valid =   (iformat === InstrFormat.R) ||
                     (iformat === InstrFormat.I) ||
                     (iformat === InstrFormat.U) ||
                     (iformat === InstrFormat.J) ||
                     (iformat === InstrFormat.Shamt)

    val rs1 = io.d2e.rs1_data
    val rs2 = io.d2e.rs2_data

    val imm = io.d2e.imm

    val alu = new Area {
        val rd_wr    = False
        val rd_wdata = UInt(32 bits)

        val op1 = S(rs1)
        val op2 = S(rs2)

        rd_wdata := U( (sub ? ( op1 @@ S"1") | (op1 @@ S"0") ) +
                       (sub ? (~op2 @@ S"1") | (op2 @@ S"0") ))(32 downto 1)

        switch(itype){
            is(InstrType.ALU_ADD){
                rd_wr    := True
            }
            is(InstrType.ALU){
                switch(funct3){
                    is(B"010"){  // SLT,
                        rd_wr    := True
                        rd_wdata := U(op1 < op2).resize(32)
                    }
                    is(B"011"){         // SLTU
                        rd_wr    := True
                        rd_wdata := U(U(op1) < U(op2)).resize(32)
                    }
                    is(B"100"){         // XOR
                        rd_wr    := True
                        rd_wdata := U(op1 ^ op2)
                    }
                    is(B"110"){         // OR
                        rd_wr    := True
                        rd_wdata := U(op1 | op2)
                    }
                    is(B"111"){         // AND
                        rd_wr    := True
                        rd_wdata := U(op1 & op2)
                    }
                }
            }
            is(InstrType.MULDIV){
                if (config.hasMul) {
                    val op1 = S(rs1).resize(33)
                    val op2 = S(rs2).resize(33)
                    val upper = False

                    switch(funct3){
                        is(B"000"){         // MUL
                            rd_wr   := True
                            upper   := False
                            op1 := S(U(rs1).resize(33))
                            op2 := S(U(rs2).resize(33))
                        }
                        is(B"001"){         // MULH
                            rd_wr   := True
                            upper   := True
                        }
                        is(B"010"){         // MULHSU
                            rd_wr   := True
                            upper   := True
                            op2 := S(U(rs2).resize(33))
                        }
                        is(B"011"){         // MULHU
                            rd_wr   := True
                            upper   := True
                            op1 := S(U(rs1).resize(33))
                            op2 := S(U(rs2).resize(33))
                        }
                    }

                    val result = op1 * op2
                    rd_wdata := upper ? U(result(63 downto 32)) | U(result(31 downto 0))
                }
            }
        }
    }

    val shift = new Area {
        val rd_wr       = (itype === InstrType.SHIFT)
        val rd_wdata    = UInt(32 bits)
        val shamt       = U(rs2(4 downto 0))
        val shleft      = !funct3(2)
        val op1         = instr(30) ? S(rs1(31) ## rs1) | S(B"0" ## rs1)

        rd_wdata := U(shleft ? (op1 |<< shamt) | (op1 |>> shamt))(31 downto 0)
    }

    val jump = new Area {

        val rd_wr    = False
        val rd_wdata = U(0, 32 bits)

        val take_jump     = False
        val pc_jump_valid = False
        val pc_jump       = UInt(32 bits)

        val clr_lsb = False

        val pc       = io.d2e.pc
        val pc_op1   = S(pc)
        val pc_plus4 = pc + 4

        switch(itype){
            is(InstrType.B){
                val op1 = S( (rs1(31) & !funct3(1)) ## rs1 )
                val op2 = S( (rs2(31) & !funct3(1)) ## rs2 )

                val rs1_eq_rs2 = (rs1 === rs2)
                val op1_lt_op2 = (op1 < op2)

                val branch_cond = False
                switch(funct3){
                    is(B"000")       { branch_cond :=  rs1_eq_rs2 } // BEQ
                    is(B"001")       { branch_cond := !rs1_eq_rs2 } // BNE
                    is(B"100",B"110"){ branch_cond :=  op1_lt_op2 } // BLT, BLTU
                    is(B"101",B"111"){ branch_cond := !op1_lt_op2 } // BGE, BGEU
                }

                pc_jump_valid := True
                take_jump     := branch_cond
            }
            is(InstrType.JAL){
                pc_jump_valid := True
                take_jump     := True

                rd_wr    := True
                rd_wdata := pc_plus4
            }
            is(InstrType.JALR){
                pc_jump_valid := True
                take_jump     := True

                pc_op1   := S(rs1)
                clr_lsb  := True
                rd_wr    := True
                rd_wdata := pc_plus4
            }
        }

        // Clear LSB for JALR ops
        pc_jump := (take_jump ? U(pc_op1 + imm) |
                                pc_plus4        ) & ~(U(clr_lsb).resize(32))

    }

    val lsu = new Area {
        object LsuState extends SpinalEnum {
            val Idle            = newElement()
            val WaitRsp         = newElement()
        }

        val cur_state = Reg(LsuState()) init(LsuState.Idle)

        val lsu_stall = False

        val rd_wr    = False
        val size     = funct3(1 downto 0)

        val lsu_addr = U(S(rs1) + imm)

        io.data_req.valid   := False
        io.data_req.addr    := lsu_addr(31 downto 2) @@ "00"
        io.data_req.wr      := False
        io.data_req.size    := size
        io.data_req.data    := rs2

        switch(cur_state){
            is(LsuState.Idle){
                when(io.d2e.valid && (itype === InstrType.L || itype === InstrType.S)){
                    io.data_req.valid   := True
                    io.data_req.wr      := (itype === InstrType.S)

                    lsu_stall := True

                    when(io.data_req.ready){
                        when(itype === InstrType.S){
                            lsu_stall := False
                        }
                        .otherwise{
                            cur_state := LsuState.WaitRsp
                        }
                    }
                }
            }
            is(LsuState.WaitRsp){
                lsu_stall := True

                when(io.data_rsp.valid){
                    lsu_stall := False
                    rd_wr     := True

                    cur_state := LsuState.Idle
                }
            }
        }

        val rsp_data_shift_adj = Bits(32 bits)
        rsp_data_shift_adj := io.data_rsp.data >> (lsu_addr(1 downto 0) * 8)

        val rd_wdata = ( (funct3 === B"000") ? B(S(rsp_data_shift_adj( 7 downto 0)).resize(32)) |
                       ( (funct3 === B"100") ? B(U(rsp_data_shift_adj( 7 downto 0)).resize(32)) |
                       ( (funct3 === B"001") ? B(S(rsp_data_shift_adj(15 downto 0)).resize(32)) |
                       ( (funct3 === B"101") ? B(U(rsp_data_shift_adj(15 downto 0)).resize(32)) |
                                                   rsp_data_shift_adj))))
    }

    val rd_wr    = io.d2e.valid && (alu.rd_wr | jump.rd_wr | shift.rd_wr | lsu.rd_wr) && (rd_addr =/= 0)
    val rd_waddr = rd_wr ? rd_addr | U"5'd0"
    val rd_wdata = B((alu.rd_wdata.range   -> alu.rd_wr))   & B(alu.rd_wdata)   |
                   B((jump.rd_wdata.range  -> jump.rd_wr))  & B(jump.rd_wdata)  |
                   B((shift.rd_wdata.range -> shift.rd_wr)) & B(shift.rd_wdata) |
                   B((lsu.rd_wdata.range   -> lsu.rd_wr))   & B(lsu.rd_wdata)

    io.e2d.stall         := lsu.lsu_stall
    io.e2d.pc_jump_valid := io.d2e.valid && jump.pc_jump_valid
    io.e2d.pc_jump       := jump.pc_jump

    io.e2d.rd_addr_valid := io.d2e.valid && rd_valid
    io.e2d.rd_addr       := rd_addr

    io.e2f.rd_addr_valid := io.d2e.valid && rd_valid
    io.e2f.rd_addr       := rd_addr

    // Write to RegFile
    io.w2r.rd_wr        := rd_wr
    io.w2r.rd_wr_addr   := rd_waddr
    io.w2r.rd_wr_data   := rd_wdata

    val formal = if (config.hasFormal) new Area {

        io.rvfi.valid := exe_end

        when(exe_start){
            io.rvfi.order     := io.d2e.rvfi.order
            io.rvfi.pc_rdata  := io.d2e.rvfi.pc_rdata
            io.rvfi.insn      := io.d2e.rvfi.insn
            io.rvfi.trap      := io.d2e.rvfi.trap
            io.rvfi.halt      := io.d2e.rvfi.halt
            io.rvfi.intr      := io.d2e.rvfi.intr

            io.rvfi.rs1_addr  := io.d2e.rvfi.rs1_addr
            io.rvfi.rs2_addr  := io.d2e.rvfi.rs2_addr
            io.rvfi.rd_addr   := io.d2e.rvfi.rd_addr

            io.rvfi.rs1_rdata := io.d2e.rvfi.rs1_rdata
            io.rvfi.rs2_rdata := io.d2e.rvfi.rs2_rdata
            io.rvfi.rd_wdata  := 0

            io.rvfi.mem_addr  := 0
            io.rvfi.mem_rmask := 0
            io.rvfi.mem_rdata := 0
            io.rvfi.mem_wmask := 0
            io.rvfi.mem_wdata := 0
        }

        when(rd_wr){
            io.rvfi.rd_addr   := rd_waddr
            io.rvfi.rd_wdata  := rd_wdata
        }

        when(exe_end){
            when(io.e2d.pc_jump_valid){
                io.rvfi.pc_wdata  := io.e2d.pc_jump
            }
            .otherwise{
                io.rvfi.pc_wdata  := io.d2e.rvfi.pc_rdata + 4
            }
        }

        switch(itype){
            is(InstrType.B, InstrType.JAL, InstrType.JALR){
                when(exe_end && io.e2d.pc_jump_valid && io.e2d.pc_jump(1 downto 0) =/= "00"){
                    io.rvfi.trap := True
                }
            }
            is(InstrType.L){
                when(io.data_req.valid && io.data_req.ready){
                    io.rvfi.mem_addr  := io.data_req.addr
                    io.rvfi.mem_rmask := ((io.data_req.size === B"00") ? B"0001" |
                                         ((io.data_req.size === B"01") ? B"0011" |
                                                                         B"1111")) |<< lsu.lsu_addr(1 downto 0)

                    io.rvfi.trap      := (io.data_req.size === B"01" && lsu.lsu_addr(0)) |
                                         (io.data_req.size === B"10" && lsu.lsu_addr(1 downto 0) =/= "00")
                }

                when(io.data_rsp.valid){
                    io.rvfi.mem_rdata := io.data_rsp.data
                }
            }
            is(InstrType.S){
                when(io.data_req.valid && io.data_req.ready){
                    io.rvfi.mem_addr  := io.data_req.addr
                    io.rvfi.mem_wmask := ((io.data_req.size === B"00") ? B"0001" |
                                         ((io.data_req.size === B"01") ? B"0011" |
                                                                         B"1111")) |<< lsu.lsu_addr(1 downto 0)

                    io.rvfi.mem_wdata := ((io.data_req.size === B"00") ? io.data_req.data(7 downto 0).resize(32)  |
                                         ((io.data_req.size === B"01") ? io.data_req.data(15 downto 0).resize(32) |
                                                                         io.data_req.data)) |<< (lsu.lsu_addr(1 downto 0) * 8)

                    io.rvfi.trap      := (io.data_req.size === B"01" && lsu.lsu_addr(0)) |
                                         (io.data_req.size === B"10" && lsu.lsu_addr(1 downto 0) =/= "00")

                }
            }
        }

    } else null

}


